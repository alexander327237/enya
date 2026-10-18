package main

import (
	"archive/zip"
	"bytes"
	"context"
	"encoding/base64"
	"encoding/csv"
	"encoding/json"
	"encoding/xml"
	"fmt"
	"io"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"strings"
	"time"
	"unicode/utf8"
)

// Вложения из Макса: фото уходят нейросети картинкой, документы — текстом,
// голосовые — расшифровкой от Макса, остальное — коротким описанием.

const (
	maxAttachmentBytes = 25 << 20 // больше не скачиваем
	maxDocChars        = 60000    // сколько текста документа отдаём нейросети
	maxImages          = 4
)

type MaxAttachment struct {
	Type          string          `json:"type"`
	Payload       json.RawMessage `json:"payload"`
	Filename      string          `json:"filename"`
	Size          int64           `json:"size"`
	Transcription string          `json:"transcription"`
	Title         string          `json:"title"`
	Description   string          `json:"description"`
	Latitude      float64         `json:"latitude"`
	Longitude     float64         `json:"longitude"`
	Thumbnail     *struct {
		URL string `json:"url"`
	} `json:"thumbnail"`
}

type attachPayload struct {
	URL   string `json:"url"`
	Token string `json:"token"`
	// contact
	VcfInfo  string   `json:"vcf_info"`
	MaxInfo  *MaxUser `json:"max_info"`
	VcfPhone string   `json:"vcf_phone"`
}

// Prepared — что получилось из вложений: картинки для модели со зрением и текст для всех.
type Prepared struct {
	Images []ImagePart
	Text   string // описание/содержимое вложений, добавляется к сообщению
	Notes  []string
}

func (p Prepared) Empty() bool { return len(p.Images) == 0 && p.Text == "" }

func (m *MaxClient) download(ctx context.Context, rawURL string) ([]byte, string, error) {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, rawURL, nil)
	if err != nil {
		return nil, "", err
	}
	resp, err := m.hc.Do(req)
	if err != nil {
		return nil, "", err
	}
	defer resp.Body.Close()
	if resp.StatusCode/100 != 2 {
		return nil, "", fmt.Errorf("HTTP %d при скачивании вложения", resp.StatusCode)
	}
	data, err := io.ReadAll(io.LimitReader(resp.Body, maxAttachmentBytes+1))
	if err != nil {
		return nil, "", err
	}
	if len(data) > maxAttachmentBytes {
		return nil, "", fmt.Errorf("файл больше %d МБ", maxAttachmentBytes>>20)
	}
	return data, resp.Header.Get("Content-Type"), nil
}

func imageMIME(data []byte, name string) string {
	switch {
	case bytes.HasPrefix(data, []byte{0xFF, 0xD8}):
		return "image/jpeg"
	case bytes.HasPrefix(data, []byte("\x89PNG")):
		return "image/png"
	case bytes.HasPrefix(data, []byte("GIF8")):
		return "image/gif"
	case len(data) > 12 && string(data[8:12]) == "WEBP":
		return "image/webp"
	}
	switch strings.ToLower(filepath.Ext(name)) {
	case ".jpg", ".jpeg":
		return "image/jpeg"
	case ".png":
		return "image/png"
	case ".gif":
		return "image/gif"
	case ".webp":
		return "image/webp"
	}
	return ""
}

func dataURL(mime string, data []byte) string {
	return "data:" + mime + ";base64," + base64.StdEncoding.EncodeToString(data)
}

// PrepareAttachments скачивает и разбирает вложения. Ошибки не роняют ответ: о них пишется в Notes.
func (b *Bot) PrepareAttachments(ctx context.Context, mc *MaxClient, atts []MaxAttachment) Prepared {
	var p Prepared
	var parts []string
	for _, a := range atts {
		var pl attachPayload
		_ = json.Unmarshal(a.Payload, &pl)
		switch a.Type {
		case "image", "sticker":
			if len(p.Images) >= maxImages {
				p.Notes = append(p.Notes, "картинок больше четырёх — остальные пропущены")
				continue
			}
			data, ct, err := mc.download(ctx, pl.URL)
			if err != nil {
				p.Notes = append(p.Notes, "не удалось скачать фото: "+err.Error())
				continue
			}
			mime := imageMIME(data, "")
			if mime == "" && strings.HasPrefix(ct, "image/") {
				mime = ct
			}
			if mime == "" {
				p.Notes = append(p.Notes, "фото в неподдерживаемом формате")
				continue
			}
			p.Images = append(p.Images, ImagePart{URL: dataURL(mime, data)})
		case "audio":
			if t := strings.TrimSpace(a.Transcription); t != "" {
				parts = append(parts, "Голосовое сообщение (расшифровка): "+t)
			} else {
				p.Notes = append(p.Notes, "голосовое без расшифровки — Макс не распознал речь, текст прочитать не могу")
			}
		case "video":
			if a.Thumbnail != nil && a.Thumbnail.URL != "" && len(p.Images) < maxImages {
				if data, _, err := mc.download(ctx, a.Thumbnail.URL); err == nil {
					if mime := imageMIME(data, ""); mime != "" {
						p.Images = append(p.Images, ImagePart{URL: dataURL(mime, data)})
					}
				}
			}
			parts = append(parts, "Видео (само видео не анализируется, приложен только кадр-превью)")
		case "file":
			txt, isImg, img, err := b.extractFile(ctx, mc, a, pl)
			if err != nil {
				p.Notes = append(p.Notes, fmt.Sprintf("файл %s: %v", a.Filename, err))
				continue
			}
			if isImg {
				if len(p.Images) < maxImages {
					p.Images = append(p.Images, img)
				}
				continue
			}
			parts = append(parts, fmt.Sprintf("Файл «%s»:\n%s", a.Filename, txt))
		case "contact":
			c := "Контакт"
			if pl.MaxInfo != nil {
				c += ": " + pl.MaxInfo.DisplayName()
			}
			if pl.VcfPhone != "" {
				c += ", тел. " + pl.VcfPhone
			}
			if pl.VcfInfo != "" {
				c += "\n" + pl.VcfInfo
			}
			parts = append(parts, c)
		case "location":
			parts = append(parts, fmt.Sprintf("Геолокация: %.6f, %.6f", a.Latitude, a.Longitude))
		case "share":
			s := "Ссылка"
			if pl.URL != "" {
				s += ": " + pl.URL
			}
			if a.Title != "" {
				s += "\n" + a.Title
			}
			if a.Description != "" {
				s += "\n" + a.Description
			}
			parts = append(parts, s)
		case "inline_keyboard":
		default:
			p.Notes = append(p.Notes, "вложение типа "+a.Type+" не поддерживается")
		}
	}
	p.Text = strings.Join(parts, "\n\n")
	return p
}

// extractFile превращает документ в текст. Картинки, присланные файлом, возвращает как картинку.
func (b *Bot) extractFile(ctx context.Context, mc *MaxClient, a MaxAttachment, pl attachPayload) (string, bool, ImagePart, error) {
	if a.Size > maxAttachmentBytes {
		return "", false, ImagePart{}, fmt.Errorf("больше %d МБ", maxAttachmentBytes>>20)
	}
	data, _, err := mc.download(ctx, pl.URL)
	if err != nil {
		return "", false, ImagePart{}, err
	}
	if mime := imageMIME(data, a.Filename); mime != "" {
		return "", true, ImagePart{URL: dataURL(mime, data)}, nil
	}
	txt, err := extractText(ctx, a.Filename, data)
	if err != nil {
		return "", false, ImagePart{}, err
	}
	txt = strings.TrimSpace(txt)
	if txt == "" {
		return "", false, ImagePart{}, fmt.Errorf("текста внутри не нашлось (скан? тогда пришлите страницы как фото)")
	}
	if r := []rune(txt); len(r) > maxDocChars {
		txt = string(r[:maxDocChars]) + "\n…(документ обрезан)"
	}
	return txt, false, ImagePart{}, nil
}

func extractText(ctx context.Context, name string, data []byte) (string, error) {
	ext := strings.ToLower(filepath.Ext(name))
	switch ext {
	case ".pdf":
		return pdfText(ctx, data)
	case ".docx":
		return docxText(data)
	case ".xlsx":
		return xlsxText(data)
	case ".csv":
		return csvText(data), nil
	case ".doc", ".xls", ".rtf", ".odt", ".ods":
		return officeViaSoffice(ctx, name, data)
	case ".zip", ".rar", ".7z", ".exe", ".apk", ".mp3", ".ogg", ".m4a", ".mp4", ".mov":
		return "", fmt.Errorf("этот тип файла не читаю")
	}
	if utf8.Valid(data) && !bytes.ContainsRune(data[:min(len(data), 4096)], 0) {
		return string(data), nil
	}
	return "", fmt.Errorf("не понял формат файла")
}

func pdfText(ctx context.Context, data []byte) (string, error) {
	if _, err := exec.LookPath("pdftotext"); err != nil {
		return "", fmt.Errorf("на компьютере не установлен pdftotext (пакет poppler-utils)")
	}
	cctx, cancel := context.WithTimeout(ctx, 60*time.Second)
	defer cancel()
	cmd := exec.CommandContext(cctx, "pdftotext", "-layout", "-", "-")
	cmd.Stdin = bytes.NewReader(data)
	out, err := cmd.Output()
	if err != nil {
		return "", fmt.Errorf("не удалось прочитать PDF")
	}
	return string(out), nil
}

func officeViaSoffice(ctx context.Context, name string, data []byte) (string, error) {
	if _, err := exec.LookPath("soffice"); err != nil {
		return "", fmt.Errorf("старый формат Office: нужен LibreOffice (пакет libreoffice-core), либо сохраните как .docx/.xlsx")
	}
	dir, err := os.MkdirTemp("", "bb-office-")
	if err != nil {
		return "", err
	}
	defer os.RemoveAll(dir)
	src := filepath.Join(dir, "in"+strings.ToLower(filepath.Ext(name)))
	if err := os.WriteFile(src, data, 0o600); err != nil {
		return "", err
	}
	cctx, cancel := context.WithTimeout(ctx, 120*time.Second)
	defer cancel()
	cmd := exec.CommandContext(cctx, "soffice", "--headless", "--convert-to", "txt:Text", "--outdir", dir, src)
	if err := cmd.Run(); err != nil {
		return "", fmt.Errorf("не удалось сконвертировать файл")
	}
	out, err := os.ReadFile(filepath.Join(dir, "in.txt"))
	if err != nil {
		return "", fmt.Errorf("не удалось сконвертировать файл")
	}
	return string(out), nil
}

var xmlTag = regexp.MustCompile(`<[^>]+>`)

func docxText(data []byte) (string, error) {
	zr, err := zip.NewReader(bytes.NewReader(data), int64(len(data)))
	if err != nil {
		return "", fmt.Errorf("повреждённый .docx")
	}
	var sb strings.Builder
	for _, f := range zr.File {
		if f.Name != "word/document.xml" {
			continue
		}
		rc, err := f.Open()
		if err != nil {
			return "", err
		}
		raw, _ := io.ReadAll(io.LimitReader(rc, 50<<20))
		rc.Close()
		s := string(raw)
		s = strings.ReplaceAll(s, "</w:p>", "\n")
		s = strings.ReplaceAll(s, "</w:tc>", "\t")
		s = strings.ReplaceAll(s, "<w:tab/>", "\t")
		s = xmlTag.ReplaceAllString(s, "")
		sb.WriteString(unescapeXML(s))
	}
	if sb.Len() == 0 {
		return "", fmt.Errorf("в .docx не нашлось текста")
	}
	return sb.String(), nil
}

func unescapeXML(s string) string {
	var out strings.Builder
	if err := xml.EscapeText(&out, nil); err != nil {
		return s
	}
	r := strings.NewReplacer("&lt;", "<", "&gt;", ">", "&quot;", `"`, "&apos;", "'", "&amp;", "&")
	return r.Replace(s)
}

// xlsxText: все листы, строки через перевод, ячейки через табуляцию.
func xlsxText(data []byte) (string, error) {
	zr, err := zip.NewReader(bytes.NewReader(data), int64(len(data)))
	if err != nil {
		return "", fmt.Errorf("повреждённый .xlsx")
	}
	files := map[string][]byte{}
	for _, f := range zr.File {
		if strings.HasPrefix(f.Name, "xl/") {
			rc, err := f.Open()
			if err != nil {
				continue
			}
			files[f.Name], _ = io.ReadAll(io.LimitReader(rc, 50<<20))
			rc.Close()
		}
	}
	var shared []string
	if raw, ok := files["xl/sharedStrings.xml"]; ok {
		var ss struct {
			SI []struct {
				T []string `xml:"t"`
				R []struct {
					T string `xml:"t"`
				} `xml:"r"`
			} `xml:"si"`
		}
		if xml.Unmarshal(raw, &ss) == nil {
			for _, si := range ss.SI {
				s := strings.Join(si.T, "")
				for _, r := range si.R {
					s += r.T
				}
				shared = append(shared, s)
			}
		}
	}
	var sb strings.Builder
	n := 0
	for name, raw := range files {
		if !strings.HasPrefix(name, "xl/worksheets/sheet") {
			continue
		}
		var ws struct {
			Rows []struct {
				C []struct {
					T  string `xml:"t,attr"`
					V  string `xml:"v"`
					IS struct {
						T string `xml:"t"`
					} `xml:"is"`
				} `xml:"c"`
			} `xml:"sheetData>row"`
		}
		if xml.Unmarshal(raw, &ws) != nil {
			continue
		}
		n++
		sb.WriteString(fmt.Sprintf("--- Лист %d ---\n", n))
		for _, row := range ws.Rows {
			var cells []string
			for _, c := range row.C {
				v := c.V
				if c.T == "s" {
					var i int
					if _, err := fmt.Sscanf(c.V, "%d", &i); err == nil && i >= 0 && i < len(shared) {
						v = shared[i]
					}
				} else if c.T == "inlineStr" {
					v = c.IS.T
				}
				cells = append(cells, v)
			}
			line := strings.TrimRight(strings.Join(cells, "\t"), "\t")
			if line != "" {
				sb.WriteString(line + "\n")
			}
		}
	}
	if n == 0 {
		return "", fmt.Errorf("в .xlsx не нашлось листов")
	}
	return sb.String(), nil
}

func csvText(data []byte) string {
	r := csv.NewReader(bytes.NewReader(data))
	r.Comma = ';'
	r.FieldsPerRecord = -1
	r.LazyQuotes = true
	rows, err := r.ReadAll()
	if err != nil || len(rows) == 0 || (len(rows) > 0 && len(rows[0]) == 1) {
		r = csv.NewReader(bytes.NewReader(data))
		r.FieldsPerRecord = -1
		r.LazyQuotes = true
		if rows2, err2 := r.ReadAll(); err2 == nil {
			rows = rows2
		}
	}
	var sb strings.Builder
	for _, row := range rows {
		sb.WriteString(strings.Join(row, "\t") + "\n")
	}
	if sb.Len() == 0 {
		return string(data)
	}
	return sb.String()
}
