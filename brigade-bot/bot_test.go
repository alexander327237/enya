package main

import (
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"
)

func msg(chatType string, chatID, userID int64, text string) MaxMessage {
	var m MaxMessage
	m.Recipient.ChatType = chatType
	m.Recipient.ChatID = chatID
	m.Sender.UserID = userID
	m.Sender.FirstName = "Иван"
	m.Body.Text = text
	m.Body.Mid = "mid1"
	return m
}

func TestDecide(t *testing.T) {
	b := &Bot{}
	cfg := defaultConfig()
	cfg.AllowedUsers = []Person{{ID: 10, Name: "Я"}}
	cfg.AllowedChats = []Person{{ID: 500, Name: "Бригада"}}
	const botID = 1

	cases := []struct {
		name    string
		m       MaxMessage
		respond bool
		reason  string
		builtin string
		text    string
	}{
		{"личка, разрешён", msg("dialog", 77, 10, "привет"), true, "", "", "привет"},
		{"личка, чужой", msg("dialog", 78, 11, "привет"), false, "user", "", ""},
		{"личка, чужой /id", msg("dialog", 78, 11, "/id"), false, "", "id", ""},
		{"группа без обращения", msg("chat", 500, 10, "кто завтра на объект?"), false, "skip", "", ""},
		{"группа, триггер", msg("chat", 500, 10, "Бот, сколько стоит кран"), true, "", "", "сколько стоит кран"},
		{"триггер внутри слова не считается", msg("chat", 500, 10, "ботинки купил"), false, "skip", "", ""},
		{"группа, чужой человек", msg("chat", 500, 11, "бот, привет"), false, "user", "", ""},
		{"неразрешённый чат", msg("chat", 600, 10, "бот, привет"), false, "chat", "", ""},
		{"команда в группе", msg("chat", 500, 10, "/смета стояк"), true, "", "", "/смета стояк"},
		{"упоминание", msg("chat", 500, 10, "@brig_bot посчитай"), true, "", "", "посчитай"},
		{"свои сообщения", msg("chat", 500, botID, "бот, x"), false, "skip", "", ""},
		{"помощь", msg("dialog", 77, 10, "/помощь"), false, "", "help", ""},
	}
	for _, c := range cases {
		d := b.decide(cfg, c.m, botID, "brig_bot")
		if d.Respond != c.respond || d.Reason != c.reason || d.Builtin != c.builtin || (c.respond && d.Text != c.text) {
			t.Errorf("%s: got %+v", c.name, d)
		}
	}

	// Ответ на сообщение бота в группе считается обращением.
	m := msg("chat", 500, 10, "а если Ду32?")
	m.Link = &struct {
		Type   string   `json:"type"`
		Sender *MaxUser `json:"sender"`
	}{Type: "reply", Sender: &MaxUser{UserID: botID}}
	if d := b.decide(cfg, m, botID, "brig_bot"); !d.Respond {
		t.Errorf("reply to bot: %+v", d)
	}

	// Открытый режим: чужой человек в разрешённом чате получает ответ, в чужом чате — нет.
	cfg.OpenInAllowedChats = true
	if d := b.decide(cfg, msg("chat", 500, 11, "бот, привет"), botID, "brig_bot"); !d.Respond {
		t.Errorf("open chat: %+v", d)
	}
	if d := b.decide(cfg, msg("chat", 600, 11, "бот, привет"), botID, "brig_bot"); d.Reason != "chat" {
		t.Errorf("open mode, foreign chat: %+v", d)
	}
}

func TestSplitText(t *testing.T) {
	s := strings.Repeat("а", 5000)
	parts := splitText(s, 3900)
	if len(parts) != 2 || len([]rune(parts[0])) != 3900 {
		t.Fatalf("parts: %d", len(parts))
	}
	s2 := strings.Repeat("строка текста\n", 400)
	for _, p := range splitText(s2, 3900) {
		if len([]rune(p)) > 3900 {
			t.Fatal("part too long")
		}
	}
}

func TestMatchCommand(t *testing.T) {
	cmds := defaultConfig().Commands
	c, rest, ok := matchCommand(cmds, "/Смета замена стояка")
	if !ok || c.Name != "смета" || rest != "замена стояка" {
		t.Fatalf("%v %q %v", c.Name, rest, ok)
	}
	if _, _, ok := matchCommand(cmds, "/нет такой"); ok {
		t.Fatal("unexpected match")
	}
}

func TestQuota(t *testing.T) {
	b := NewBot(&Store{cfg: defaultConfig()})
	cfg := defaultConfig()
	cfg.DailyLimitPerUser, cfg.DailyLimitTotal = 2, 3
	for i := 0; i < 2; i++ {
		if ok, _ := b.takeQuota(cfg, 1); !ok {
			t.Fatal("quota too early")
		}
	}
	if ok, _ := b.takeQuota(cfg, 1); ok {
		t.Fatal("per-user limit not applied")
	}
	if ok, _ := b.takeQuota(cfg, 2); !ok {
		t.Fatal("other user blocked")
	}
	if ok, _ := b.takeQuota(cfg, 3); ok {
		t.Fatal("total limit not applied")
	}
}

// Полный цикл: фейковый Макс → бот → фейковая OpenAI-совместимая нейросеть → ответ в чат.
func TestEndToEnd(t *testing.T) {
	var mu sync.Mutex
	var sent []map[string]any
	var sentChat []string
	served := false

	maxSrv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("Authorization") != "TOKEN" {
			w.WriteHeader(401)
			return
		}
		switch {
		case r.URL.Path == "/me":
			_, _ = io.WriteString(w, `{"user_id":1,"first_name":"Бригада","username":"brig_bot","is_bot":true}`)
		case r.URL.Path == "/updates":
			mu.Lock()
			first := !served
			served = true
			mu.Unlock()
			if first {
				_, _ = io.WriteString(w, `{"marker":5,"updates":[
				 {"update_type":"message_created","timestamp":1,"message":{"sender":{"user_id":10,"first_name":"Иван"},"recipient":{"chat_id":500,"chat_type":"chat"},"body":{"mid":"m1","text":"бот, сколько электродов на 10 стыков Ду50?"}}},
				 {"update_type":"message_created","timestamp":2,"message":{"sender":{"user_id":99,"first_name":"Чужой"},"recipient":{"chat_id":500,"chat_type":"chat"},"body":{"mid":"m2","text":"бот, напиши стих"}}},
				 {"update_type":"bot_added","timestamp":3,"chat_id":700,"user":{"user_id":10,"first_name":"Иван"},"is_channel":false}
				]}`)
				return
			}
			time.Sleep(50 * time.Millisecond)
			_, _ = io.WriteString(w, `{"marker":5,"updates":[]}`)
		case r.URL.Path == "/messages" && r.Method == http.MethodPost:
			var body map[string]any
			_ = json.NewDecoder(r.Body).Decode(&body)
			mu.Lock()
			sent = append(sent, body)
			sentChat = append(sentChat, r.URL.Query().Get("chat_id"))
			mu.Unlock()
			_, _ = io.WriteString(w, `{"message":{}}`)
		default:
			w.WriteHeader(404)
		}
	}))
	defer maxSrv.Close()

	var gotPrompt string
	llmSrv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		var req struct {
			Messages []ChatMsg `json:"messages"`
		}
		_ = json.NewDecoder(r.Body).Decode(&req)
		mu.Lock()
		gotPrompt = req.Messages[len(req.Messages)-1].Content
		mu.Unlock()
		_, _ = io.WriteString(w, `{"choices":[{"message":{"role":"assistant","content":"Примерно 1 кг электродов."}}]}`)
	}))
	defer llmSrv.Close()

	old := maxAPIBase
	maxAPIBase = maxSrv.URL
	defer func() { maxAPIBase = old }()

	store, err := LoadStore(filepath.Join(t.TempDir(), "cfg.json"))
	if err != nil {
		t.Fatal(err)
	}
	_ = store.Update(func(c *Config) {
		c.MaxToken = "TOKEN"
		c.Provider = "openai"
		c.OpenAI = OpenAICfg{BaseURL: llmSrv.URL, Model: "test"}
		c.AllowedUsers = []Person{{ID: 10, Name: "Иван"}}
		c.AllowedChats = []Person{{ID: 500, Name: "Бригада"}}
	})
	bot := NewBot(store)
	if err := bot.Start(); err != nil {
		t.Fatal(err)
	}
	defer bot.Stop()

	deadline := time.Now().Add(5 * time.Second)
	for time.Now().Before(deadline) {
		mu.Lock()
		n := len(sent)
		mu.Unlock()
		if n >= 1 && len(bot.Status().Pending) >= 2 {
			break
		}
		time.Sleep(20 * time.Millisecond)
	}
	mu.Lock()
	defer mu.Unlock()
	if len(sent) != 1 {
		t.Fatalf("ожидался 1 ответ, отправлено %d: %v", len(sent), sent)
	}
	if sent[0]["text"] != "Примерно 1 кг электродов." || sentChat[0] != "500" {
		t.Fatalf("ответ: %v в чат %s", sent[0], sentChat[0])
	}
	if !strings.Contains(gotPrompt, "сколько электродов") || strings.HasPrefix(gotPrompt, "бот") {
		t.Fatalf("запрос к нейросети: %q", gotPrompt)
	}
	st := bot.Status()
	kinds := map[string]bool{}
	for _, p := range st.Pending {
		kinds[p.Kind] = true
	}
	if !kinds["user"] || !kinds["chat"] {
		t.Fatalf("pending: %+v", st.Pending)
	}
}

func TestPanelGuard(t *testing.T) {
	store, _ := LoadStore(filepath.Join(t.TempDir(), "cfg.json"))
	_ = store.Update(func(c *Config) { c.MaxToken = "SECRET" })
	srv := httptest.NewServer((&Server{store: store, bot: NewBot(store)}).routes())
	defer srv.Close()

	// Чужой Host — отказ.
	req, _ := http.NewRequest("GET", srv.URL+"/api/config", nil)
	req.Host = "evil.example"
	if r, _ := http.DefaultClient.Do(req); r.StatusCode != 403 {
		t.Fatalf("host guard: %d", r.StatusCode)
	}
	// POST без заголовка панели — отказ.
	r, _ := http.Post(srv.URL+"/api/stop", "application/json", strings.NewReader("{}"))
	if r.StatusCode != 403 {
		t.Fatalf("csrf guard: %d", r.StatusCode)
	}
	// Токен наружу не отдаётся, а сохранение маски не затирает его.
	r, _ = http.Get(srv.URL + "/api/config")
	var c Config
	_ = json.NewDecoder(r.Body).Decode(&c)
	if c.MaxToken != secretMask {
		t.Fatalf("token leaked: %q", c.MaxToken)
	}
	b, _ := json.Marshal(c)
	req, _ = http.NewRequest("POST", srv.URL+"/api/config", strings.NewReader(string(b)))
	req.Header.Set("X-Panel", "1")
	if r, _ := http.DefaultClient.Do(req); r.StatusCode != 200 {
		t.Fatalf("save: %d", r.StatusCode)
	}
	if store.Get().MaxToken != "SECRET" {
		t.Fatal("token overwritten by mask")
	}
}
