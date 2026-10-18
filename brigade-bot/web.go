package main

import (
	"context"
	_ "embed"
	"encoding/json"
	"net"
	"net/http"
	"strings"
	"time"
)

//go:embed index.html
var indexHTML []byte

//go:embed logo.svg
var logoSVG []byte

const secretMask = "••••••••"

type Server struct {
	store *Store
	bot   *Bot
}

// Панель доступна только с этого компьютера. Проверка Host защищает от DNS-rebinding,
// заголовок X-Panel — от запросов с чужих сайтов, открытых в том же браузере.
func (s *Server) guard(next http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		host := r.Host
		if h, _, err := net.SplitHostPort(host); err == nil {
			host = h
		}
		if host != "127.0.0.1" && host != "localhost" {
			http.Error(w, "forbidden", http.StatusForbidden)
			return
		}
		if r.Method != http.MethodGet && r.Header.Get("X-Panel") != "1" {
			http.Error(w, "forbidden", http.StatusForbidden)
			return
		}
		next(w, r)
	}
}

func writeJSON(w http.ResponseWriter, v any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	_ = json.NewEncoder(w).Encode(v)
}

func writeErr(w http.ResponseWriter, err error) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(http.StatusBadRequest)
	_ = json.NewEncoder(w).Encode(map[string]string{"error": err.Error()})
}

func mask(v string) string {
	if v == "" {
		return ""
	}
	return secretMask
}

func keep(newV, oldV string) string {
	if newV == secretMask {
		return oldV
	}
	return strings.TrimSpace(newV)
}

func (s *Server) routes() *http.ServeMux {
	mux := http.NewServeMux()
	mux.HandleFunc("/", s.guard(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/" {
			http.NotFound(w, r)
			return
		}
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		_, _ = w.Write(indexHTML)
	}))

	mux.HandleFunc("/logo.svg", s.guard(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "image/svg+xml")
		_, _ = w.Write(logoSVG)
	}))

	mux.HandleFunc("/api/config", s.guard(func(w http.ResponseWriter, r *http.Request) {
		switch r.Method {
		case http.MethodGet:
			c := s.store.Get()
			c.MaxToken = mask(c.MaxToken)
			c.GigaChat.AuthKey = mask(c.GigaChat.AuthKey)
			c.Yandex.APIKey = mask(c.Yandex.APIKey)
			c.OpenAI.APIKey = mask(c.OpenAI.APIKey)
			c.Fallback.APIKey = mask(c.Fallback.APIKey)
			writeJSON(w, c)
		case http.MethodPost:
			var in Config
			if err := json.NewDecoder(r.Body).Decode(&in); err != nil {
				writeErr(w, err)
				return
			}
			err := s.store.Update(func(c *Config) {
				in.MaxToken = keep(in.MaxToken, c.MaxToken)
				in.GigaChat.AuthKey = keep(in.GigaChat.AuthKey, c.GigaChat.AuthKey)
				in.Yandex.APIKey = keep(in.Yandex.APIKey, c.Yandex.APIKey)
				in.OpenAI.APIKey = keep(in.OpenAI.APIKey, c.OpenAI.APIKey)
				in.Fallback.APIKey = keep(in.Fallback.APIKey, c.Fallback.APIKey)
				*c = in
			})
			if err != nil {
				writeErr(w, err)
				return
			}
			writeJSON(w, map[string]bool{"ok": true})
		default:
			http.Error(w, "method", http.StatusMethodNotAllowed)
		}
	}))

	mux.HandleFunc("/api/status", s.guard(func(w http.ResponseWriter, r *http.Request) {
		writeJSON(w, s.bot.Status())
	}))

	mux.HandleFunc("/api/start", s.guard(func(w http.ResponseWriter, r *http.Request) {
		if err := s.bot.Start(); err != nil {
			writeErr(w, err)
			return
		}
		writeJSON(w, map[string]bool{"ok": true})
	}))

	mux.HandleFunc("/api/stop", s.guard(func(w http.ResponseWriter, r *http.Request) {
		s.bot.Stop()
		writeJSON(w, map[string]bool{"ok": true})
	}))

	mux.HandleFunc("/api/test-llm", s.guard(func(w http.ResponseWriter, r *http.Request) {
		var in struct {
			Prompt string `json:"prompt"`
			Target string `json:"target"` // "" — основная, "fallback" — запасная
		}
		_ = json.NewDecoder(r.Body).Decode(&in)
		if strings.TrimSpace(in.Prompt) == "" {
			in.Prompt = "Ответь одним предложением: какой уклон нужен для канализационной трубы 110 мм?"
		}
		cfg := s.store.Get()
		llm, err := newLLM(cfg, s.bot.ru, s.bot.def)
		maxTok := cfg.MaxTokens
		if in.Target == "fallback" {
			llm, err = newFallbackLLM(cfg, s.bot.ru, s.bot.def)
			maxTok = cfg.FallbackMaxTokens
		}
		if err != nil {
			writeErr(w, err)
			return
		}
		ctx, cancel := context.WithTimeout(r.Context(), 15*time.Minute)
		defer cancel()
		start := time.Now()
		out, err := llm.Chat(ctx, []ChatMsg{{Role: "system", Content: cfg.SystemPrompt}, {Role: "user", Content: in.Prompt}}, cfg.Temperature, maxTok)
		if err != nil {
			writeErr(w, err)
			return
		}
		writeJSON(w, map[string]any{"answer": out, "seconds": time.Since(start).Seconds()})
	}))

	// Разрешить или убрать человека/чат: {kind:"user"|"chat", id, name, allow:true|false}
	mux.HandleFunc("/api/access", s.guard(func(w http.ResponseWriter, r *http.Request) {
		var in struct {
			Kind  string `json:"kind"`
			ID    int64  `json:"id"`
			Name  string `json:"name"`
			Allow bool   `json:"allow"`
		}
		if err := json.NewDecoder(r.Body).Decode(&in); err != nil || in.ID == 0 || (in.Kind != "user" && in.Kind != "chat") {
			writeErr(w, errBadAccess)
			return
		}
		err := s.store.Update(func(c *Config) {
			list := &c.AllowedUsers
			if in.Kind == "chat" {
				list = &c.AllowedChats
			}
			var out []Person
			for _, p := range *list {
				if p.ID != in.ID {
					out = append(out, p)
				}
			}
			if in.Allow {
				name := strings.TrimSpace(in.Name)
				if name == "" {
					name = "без имени"
				}
				out = append(out, Person{ID: in.ID, Name: name})
			}
			*list = out
		})
		if err != nil {
			writeErr(w, err)
			return
		}
		s.bot.removePending(in.Kind, in.ID)
		writeJSON(w, map[string]bool{"ok": true})
	}))

	mux.HandleFunc("/api/timesheet", s.guard(func(w http.ResponseWriter, r *http.Request) {
		ym := r.URL.Query().Get("month")
		if ym == "" {
			ym = time.Now().Format("2006-01")
		}
		entries := s.bot.ts.Month(ym)
		if entries == nil {
			entries = []TSEntry{}
		}
		sum := Summarize(entries)
		if sum == nil {
			sum = []TSPerson{}
		}
		writeJSON(w, map[string]any{"month": ym, "title": monthTitle(ym), "entries": entries, "summary": sum})
	}))

	mux.HandleFunc("/api/timesheet/add", s.guard(func(w http.ResponseWriter, r *http.Request) {
		var in TSEntry
		if err := json.NewDecoder(r.Body).Decode(&in); err != nil {
			writeErr(w, err)
			return
		}
		in.Name = strings.TrimSpace(in.Name)
		if _, err := time.Parse("2006-01-02", in.Date); err != nil || in.Name == "" || in.Hours <= 0 || in.Hours > 24 {
			writeErr(w, simpleErr("нужны имя, дата и часы (от 0 до 24)"))
			return
		}
		in.Open, in.Start, in.End = false, "", ""
		if _, err := s.bot.ts.Add(in); err != nil {
			writeErr(w, err)
			return
		}
		writeJSON(w, map[string]bool{"ok": true})
	}))

	mux.HandleFunc("/api/timesheet/delete", s.guard(func(w http.ResponseWriter, r *http.Request) {
		var in struct {
			ID int64 `json:"id"`
		}
		_ = json.NewDecoder(r.Body).Decode(&in)
		if err := s.bot.ts.Delete(in.ID); err != nil {
			writeErr(w, err)
			return
		}
		writeJSON(w, map[string]bool{"ok": true})
	}))

	mux.HandleFunc("/api/timesheet.csv", s.guard(func(w http.ResponseWriter, r *http.Request) {
		ym := r.URL.Query().Get("month")
		if _, err := time.Parse("2006-01", ym); err != nil {
			ym = time.Now().Format("2006-01")
		}
		w.Header().Set("Content-Type", "text/csv; charset=utf-8")
		w.Header().Set("Content-Disposition", `attachment; filename="tabel-`+ym+`.csv"`)
		_, _ = w.Write(TimesheetCSV(s.bot.ts.Month(ym)))
	}))

	mux.HandleFunc("/api/dismiss", s.guard(func(w http.ResponseWriter, r *http.Request) {
		var in struct {
			Kind string `json:"kind"`
			ID   int64  `json:"id"`
		}
		_ = json.NewDecoder(r.Body).Decode(&in)
		s.bot.removePending(in.Kind, in.ID)
		writeJSON(w, map[string]bool{"ok": true})
	}))
	return mux
}

type simpleErr string

func (e simpleErr) Error() string { return string(e) }

const errBadAccess = simpleErr("неверные данные: нужен kind (user/chat) и id")
