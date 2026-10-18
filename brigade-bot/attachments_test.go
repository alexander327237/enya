package main

import (
	"context"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"strings"
	"sync"
	"testing"
	"time"
)

func TestExtractText(t *testing.T) {
	ctx := context.Background()
	cases := []struct{ file, want string }{
		{"smeta.docx", "Труба Ду25"},
		{"spec.xlsx", "Отвод 57x4"},
		{"tz.pdf", "zamena stoyaka"},
	}
	for _, c := range cases {
		data, err := os.ReadFile("testdata/" + c.file)
		if err != nil {
			t.Fatal(err)
		}
		txt, err := extractText(ctx, c.file, data)
		if err != nil || !strings.Contains(txt, c.want) {
			t.Errorf("%s: err=%v, text=%q", c.file, err, shorten(txt, 200))
		}
	}
	if txt, _ := extractText(ctx, "a.csv", []byte("Позиция;Кол-во\nТруба;12\n")); !strings.Contains(txt, "Труба\t12") {
		t.Errorf("csv: %q", txt)
	}
	if _, err := extractText(ctx, "x.zip", []byte("PK\x03\x04junk")); err == nil {
		t.Error("zip должен отклоняться")
	}
	if _, err := extractText(ctx, "bin.dat", []byte{0, 1, 2, 3}); err == nil {
		t.Error("бинарный мусор должен отклоняться")
	}
}

// Фото из чата уходит нейросети как image_url, голосовое — расшифровкой, docx — текстом.
func TestAttachmentsEndToEnd(t *testing.T) {
	var mu sync.Mutex
	var sent []string
	var gotBody map[string]any

	png := []byte("\x89PNG\r\n\x1a\nfakepngdata")
	docx, _ := os.ReadFile("testdata/smeta.docx")

	files := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/p.png":
			_, _ = w.Write(png)
		case "/s.docx":
			_, _ = w.Write(docx)
		default:
			w.WriteHeader(404)
		}
	}))
	defer files.Close()

	maxSrv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/messages" {
			var body map[string]any
			_ = json.NewDecoder(r.Body).Decode(&body)
			mu.Lock()
			sent = append(sent, body["text"].(string))
			mu.Unlock()
		}
		_, _ = io.WriteString(w, `{}`)
	}))
	defer maxSrv.Close()

	llm := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		mu.Lock()
		gotBody = nil
		_ = json.NewDecoder(r.Body).Decode(&gotBody)
		mu.Unlock()
		_, _ = io.WriteString(w, `{"choices":[{"message":{"role":"assistant","content":"Вижу схему стояка."}}]}`)
	}))
	defer llm.Close()

	old := maxAPIBase
	maxAPIBase = maxSrv.URL
	defer func() { maxAPIBase = old }()

	cfg := defaultConfig()
	cfg.Provider = "openai"
	cfg.OpenAI = OpenAICfg{BaseURL: llm.URL, Model: "x"}
	b := NewBot(&Store{cfg: cfg})
	mc := &MaxClient{token: "T", hc: http.DefaultClient}
	reply := func(s string) { _ = mc.Send(context.Background(), 77, s, "") }

	m := msg("dialog", 77, 10, "Что на фото?")
	m.Body.Attachments = []MaxAttachment{
		{Type: "image", Payload: json.RawMessage(`{"url":"` + files.URL + `/p.png","token":"t","photo_id":1}`)},
		{Type: "audio", Payload: json.RawMessage(`{"url":"` + files.URL + `/a.ogg","token":"t"}`), Transcription: "замена стояка на пятом"},
		{Type: "file", Filename: "smeta.docx", Size: int64(len(docx)), Payload: json.RawMessage(`{"url":"` + files.URL + `/s.docx","token":"t"}`)},
	}
	b.answer(context.Background(), mc, cfg, m, "Что на фото?", reply)

	mu.Lock()
	defer mu.Unlock()
	if len(sent) != 1 || sent[0] != "Вижу схему стояка." {
		t.Fatalf("ответы: %q", sent)
	}
	msgs := gotBody["messages"].([]any)
	last := msgs[len(msgs)-1].(map[string]any)
	parts, ok := last["content"].([]any)
	if !ok || len(parts) != 2 {
		t.Fatalf("ожидался текст + картинка, получили %v", last["content"])
	}
	txt := parts[0].(map[string]any)["text"].(string)
	if !strings.Contains(txt, "замена стояка на пятом") || !strings.Contains(txt, "Труба Ду25") {
		t.Fatalf("в тексте нет расшифровки/документа: %q", shorten(txt, 300))
	}
	img := parts[1].(map[string]any)["image_url"].(map[string]any)["url"].(string)
	if !strings.HasPrefix(img, "data:image/png;base64,") {
		t.Fatalf("картинка: %q", shorten(img, 60))
	}

	// Сообщение только с фото, без текста, в личке — отвечаем.
	m2 := msg("dialog", 77, 10, "")
	m2.Body.Attachments = m.Body.Attachments[:1]
	if d := b.decide(Config{AllowedUsers: []Person{{ID: 10}}}, m2, 1, "bot"); !d.Respond || d.Text == "" {
		t.Fatalf("фото без текста: %+v", d)
	}
	// В группе фото без обращения пропускаем.
	m3 := msg("chat", 500, 10, "")
	m3.Body.Attachments = m.Body.Attachments[:1]
	if d := b.decide(Config{AllowedUsers: []Person{{ID: 10}}, AllowedChats: []Person{{ID: 500}}, GroupTriggers: []string{"бот"}}, m3, 1, "bot"); d.Reason != "skip" {
		t.Fatalf("фото в группе без обращения: %+v", d)
	}
	// Голосовое в группе с обращением в расшифровке — отвечаем.
	m4 := msg("chat", 500, 10, "")
	m4.Body.Attachments = []MaxAttachment{{Type: "audio", Payload: json.RawMessage(`{"url":"x","token":"t"}`), Transcription: "Бот, сколько электродов надо?"}}
	if d := b.decide(Config{AllowedUsers: []Person{{ID: 10}}, AllowedChats: []Person{{ID: 500}}, GroupTriggers: []string{"бот"}}, m4, 1, "bot"); !d.Respond || !strings.Contains(d.Text, "электродов") {
		t.Fatalf("голосовое с обращением: %+v", d)
	}
	_ = time.Second
}
