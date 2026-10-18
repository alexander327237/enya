package main

import (
	"context"
	"fmt"
	"net/http"
	"path/filepath"
	"strings"
	"sync"
	"time"
	"unicode"
)

type LogEntry struct {
	Time string `json:"time"`
	Text string `json:"text"`
}

type Pending struct {
	Kind string `json:"kind"` // user | chat
	ID   int64  `json:"id"`
	Name string `json:"name"`
	Note string `json:"note"`
	When string `json:"when"`
}

type Bot struct {
	store *Store
	ru    *http.Client
	def   *http.Client

	mu       sync.Mutex
	running  bool
	cancel   context.CancelFunc
	botUser  MaxUser
	lastErr  string
	logs     []LogEntry
	pending  map[string]Pending
	history  map[int64][]ChatMsg // ключ — чат
	usageDay string
	usage    map[int64]int
	total    int
	lastHint map[int64]time.Time

	sem chan struct{}
	ts  *Timesheet
}

func NewBot(s *Store) *Bot {
	tsPath := ""
	if s.path != "" {
		tsPath = filepath.Join(filepath.Dir(s.path), "brigade-bot-timesheet.json")
	}
	ts, err := LoadTimesheet(tsPath)
	if err != nil {
		fmt.Println("Не удалось прочитать табель:", err)
		ts, _ = LoadTimesheet("")
		ts.path = tsPath + ".new"
	}
	return &Bot{
		ts:      ts,
		store:   s,
		ru:      newRuClient(),
		def:     newDefaultClient(),
		pending: map[string]Pending{},
		history: map[int64][]ChatMsg{},
		usage:   map[int64]int{},
		sem:     make(chan struct{}, 2),
	}
}

func (b *Bot) logf(format string, a ...any) {
	b.mu.Lock()
	defer b.mu.Unlock()
	e := LogEntry{Time: time.Now().Format("02.01 15:04:05"), Text: fmt.Sprintf(format, a...)}
	b.logs = append(b.logs, e)
	if len(b.logs) > 300 {
		b.logs = b.logs[len(b.logs)-300:]
	}
	fmt.Println(e.Time, e.Text)
}

func (b *Bot) addPending(p Pending) {
	b.mu.Lock()
	defer b.mu.Unlock()
	p.When = time.Now().Format("02.01 15:04")
	b.pending[fmt.Sprintf("%s:%d", p.Kind, p.ID)] = p
}

func (b *Bot) removePending(kind string, id int64) {
	b.mu.Lock()
	defer b.mu.Unlock()
	delete(b.pending, fmt.Sprintf("%s:%d", kind, id))
}

type Status struct {
	Running bool       `json:"running"`
	BotName string     `json:"bot_name"`
	BotUser string     `json:"bot_username"`
	LastErr string     `json:"last_error"`
	Logs    []LogEntry `json:"logs"`
	Pending []Pending  `json:"pending"`
	Today   int        `json:"today_total"`
}

func (b *Bot) Status() Status {
	b.mu.Lock()
	defer b.mu.Unlock()
	b.rollDay()
	st := Status{Running: b.running, BotName: b.botUser.DisplayName(), BotUser: b.botUser.Username, LastErr: b.lastErr, Today: b.total}
	if b.botUser.UserID == 0 {
		st.BotName = ""
	}
	st.Logs = append(st.Logs, b.logs...)
	for _, p := range b.pending {
		st.Pending = append(st.Pending, p)
	}
	return st
}

func (b *Bot) Start() error {
	cfg := b.store.Get()
	if strings.TrimSpace(cfg.MaxToken) == "" {
		return fmt.Errorf("не указан токен бота Макса")
	}
	b.mu.Lock()
	if b.running {
		b.mu.Unlock()
		return nil
	}
	ctx, cancel := context.WithCancel(context.Background())
	b.cancel = cancel
	b.running = true
	b.lastErr = ""
	b.mu.Unlock()

	mc := &MaxClient{token: strings.TrimSpace(cfg.MaxToken), hc: b.ru}
	me, err := mc.Me(ctx)
	if err != nil {
		b.Stop()
		b.mu.Lock()
		b.lastErr = "Не удалось подключиться к Максу: " + err.Error()
		b.mu.Unlock()
		return fmt.Errorf("не удалось подключиться к Максу: %w", err)
	}
	b.mu.Lock()
	b.botUser = me
	b.mu.Unlock()
	b.logf("Бот запущен: %s (@%s)", me.DisplayName(), me.Username)
	// Меню заполняем, только если оно пустое, чтобы не затереть команды, заданные вручную на business.max.ru.
	if len(me.Commands) == 0 {
		if err := mc.SetCommands(ctx, menuCommands(cfg)); err != nil {
			b.logf("Не удалось записать меню команд в Макс: %v", err)
		} else {
			b.logf("Меню команд записано в Макс")
		}
	}
	go b.loop(ctx, mc)
	return nil
}

func (b *Bot) Stop() {
	b.mu.Lock()
	defer b.mu.Unlock()
	if b.cancel != nil {
		b.cancel()
	}
	if b.running {
		b.running = false
		go b.logf("Бот остановлен")
	}
}

func (b *Bot) loop(ctx context.Context, mc *MaxClient) {
	var marker *int64
	backoff := time.Second
	for ctx.Err() == nil {
		ups, next, err := mc.Updates(ctx, marker, 30)
		if err != nil {
			if ctx.Err() != nil {
				return
			}
			b.mu.Lock()
			b.lastErr = err.Error()
			b.mu.Unlock()
			b.logf("Ошибка связи с Максом: %v (повтор через %s)", err, backoff)
			select {
			case <-time.After(backoff):
			case <-ctx.Done():
				return
			}
			if backoff < time.Minute {
				backoff *= 2
			}
			continue
		}
		backoff = time.Second
		b.mu.Lock()
		b.lastErr = ""
		b.mu.Unlock()
		if next != nil {
			marker = next
		}
		for _, u := range ups {
			b.handleUpdate(ctx, mc, u)
		}
	}
}

func (b *Bot) handleUpdate(ctx context.Context, mc *MaxClient, u MaxUpdate) {
	cfg := b.store.Get()
	switch u.UpdateType {
	case "bot_added":
		name := ""
		if u.User != nil {
			name = "добавил " + u.User.DisplayName()
		}
		b.logf("Бота добавили в чат %d (%s)", u.ChatID, name)
		if !hasPerson(cfg.AllowedChats, u.ChatID) {
			b.addPending(Pending{Kind: "chat", ID: u.ChatID, Name: fmt.Sprintf("Чат %d", u.ChatID), Note: name})
		}
	case "bot_started":
		if u.User != nil {
			b.logf("Диалог с ботом начал %s (id %d)", u.User.DisplayName(), u.User.UserID)
			if !hasPerson(cfg.AllowedUsers, u.User.UserID) {
				b.addPending(Pending{Kind: "user", ID: u.User.UserID, Name: u.User.DisplayName(), Note: "нажал «Начать»"})
			}
		}
	case "message_created":
		if u.Message != nil {
			b.handleMessage(ctx, mc, cfg, *u.Message)
		}
	}
}

// Decision — что делать с входящим сообщением.
type Decision struct {
	Respond bool   // вызывать нейросеть
	Text    string // текст запроса без слова-триггера
	Reason  string // почему отказано (для журнала)
	Builtin string // встроенная команда без нейросети
}

func (b *Bot) decide(cfg Config, m MaxMessage, botID int64, botUsername string) Decision {
	text := strings.TrimSpace(m.Body.Text)
	if m.Sender.IsBot || m.Sender.UserID == botID || text == "" {
		return Decision{Reason: "skip"}
	}
	isGroup := m.Recipient.ChatType != "" && m.Recipient.ChatType != "dialog"

	// В группе реагируем только на обращение к боту.
	if isGroup {
		addressed := false
		lower := strings.ToLower(text)
		if botUsername != "" && strings.Contains(lower, "@"+strings.ToLower(botUsername)) {
			addressed = true
			text = strings.TrimSpace(replaceFold(text, "@"+botUsername, ""))
		}
		if m.Link != nil && m.Link.Type == "reply" && m.Link.Sender != nil && m.Link.Sender.UserID == botID {
			addressed = true
		}
		if strings.HasPrefix(text, "/") {
			addressed = true
		}
		if !addressed {
			for _, t := range cfg.GroupTriggers {
				t = strings.TrimSpace(t)
				if t == "" {
					continue
				}
				if rest, ok := cutPrefixWord(text, t); ok {
					addressed = true
					text = rest
					break
				}
			}
		}
		if !addressed {
			return Decision{Reason: "skip"}
		}
	}

	// /id доступен всем: так человек узнаёт свой ID, чтобы владелец его добавил.
	cmd := strings.ToLower(firstWord(text))
	if i := strings.Index(cmd, "@"); i > 0 {
		cmd = cmd[:i] // «/id@brig_bot» → «/id»
	}
	if cmd == "/id" {
		return Decision{Builtin: "id"}
	}

	userOK := hasPerson(cfg.AllowedUsers, m.Sender.UserID)
	if isGroup {
		chatOK := hasPerson(cfg.AllowedChats, m.Recipient.ChatID)
		if !chatOK {
			return Decision{Reason: "chat"}
		}
		if !userOK && !cfg.OpenInAllowedChats {
			return Decision{Reason: "user"}
		}
	} else if !userOK {
		return Decision{Reason: "user"}
	}

	switch cmd {
	case "/help", "/помощь", "/start":
		return Decision{Builtin: "help"}
	case "/reset", "/сброс":
		return Decision{Builtin: "reset"}
	case "/limit", "/лимит":
		return Decision{Builtin: "limit"}
	}
	if kind, ok := tsCommand(cmd); ok {
		return Decision{Builtin: "ts:" + kind, Text: strings.TrimSpace(text[len(firstWord(text)):])}
	}
	return Decision{Respond: true, Text: text}
}

func (b *Bot) handleMessage(ctx context.Context, mc *MaxClient, cfg Config, m MaxMessage) {
	b.mu.Lock()
	botID, botUsername := b.botUser.UserID, b.botUser.Username
	b.mu.Unlock()
	chatID := m.Recipient.ChatID
	d := b.decide(cfg, m, botID, botUsername)

	switch d.Reason {
	case "skip":
		return
	case "chat":
		b.logf("Отклонено: чат %d не разрешён (пишет %s)", chatID, m.Sender.DisplayName())
		b.addPending(Pending{Kind: "chat", ID: chatID, Name: fmt.Sprintf("Чат %d", chatID), Note: "написал " + m.Sender.DisplayName()})
		// Подсказка в чат, не чаще раза в 10 минут, чтобы было понятно, почему бот молчит.
		if b.hintDue(chatID) {
			if err := mc.Send(ctx, chatID, fmt.Sprintf("Этот чат ещё не разрешён. Владелец бота: откройте панель → «Статус» → «Ожидают разрешения» → «Разрешить» (ID чата %d).", chatID), ""); err != nil {
				b.logf("Не удалось отправить подсказку в чат %d: %v", chatID, err)
			}
		}
		return
	case "user":
		b.logf("Отклонено: %s (id %d) нет в списке разрешённых", m.Sender.DisplayName(), m.Sender.UserID)
		b.addPending(Pending{Kind: "user", ID: m.Sender.UserID, Name: m.Sender.DisplayName(), Note: shorten(m.Body.Text, 60)})
		return
	}

	reply := func(t string) {
		if err := mc.Send(ctx, chatID, t, m.Body.Mid); err != nil {
			b.logf("Не удалось отправить ответ в чат %d: %v", chatID, err)
		}
	}

	switch d.Builtin {
	case "id":
		reply(fmt.Sprintf("Ваш ID: %d\nID этого чата: %d\nПередайте их владельцу бота, чтобы он дал доступ.", m.Sender.UserID, chatID))
		return
	case "help":
		reply(helpText(cfg))
		return
	case "reset":
		b.mu.Lock()
		delete(b.history, chatID)
		b.mu.Unlock()
		reply("Контекст разговора очищен.")
		return
	case "ts:in", "ts:out", "ts:hours", "ts:report":
		reply(b.handleTimesheet(strings.TrimPrefix(d.Builtin, "ts:"), d.Text, m, time.Now()))
		b.logf("Табель: %s %s %s", m.Sender.DisplayName(), d.Builtin, shorten(d.Text, 40))
		return
	case "limit":
		b.mu.Lock()
		b.rollDay()
		used, total := b.usage[m.Sender.UserID], b.total
		b.mu.Unlock()
		reply(fmt.Sprintf("Сегодня: ваших запросов %d из %d, всего %d из %d.", used, cfg.DailyLimitPerUser, total, cfg.DailyLimitTotal))
		return
	}

	if ok, why := b.takeQuota(cfg, m.Sender.UserID); !ok {
		reply(why)
		b.logf("Лимит: %s — %s", m.Sender.DisplayName(), why)
		return
	}

	go func() {
		b.sem <- struct{}{}
		defer func() { <-b.sem }()
		b.answer(ctx, mc, cfg, m, d.Text, reply)
	}()
}

func (b *Bot) answer(ctx context.Context, mc *MaxClient, cfg Config, m MaxMessage, text string, reply func(string)) {
	chatID := m.Recipient.ChatID
	sys := cfg.SystemPrompt
	user := text
	if c, rest, ok := matchCommand(cfg.Commands, text); ok {
		sys += "\n\nЗадача: " + c.Prompt
		user = rest
		if strings.TrimSpace(user) == "" {
			reply(fmt.Sprintf("Напишите после /%s, что нужно. Например: /%s замена стояка ХВС 5 этажей, сталь Ду25", c.Name, c.Name))
			return
		}
	}
	isGroup := m.Recipient.ChatType != "" && m.Recipient.ChatType != "dialog"
	if isGroup {
		user = m.Sender.DisplayName() + ": " + user
	}

	b.mu.Lock()
	hist := append([]ChatMsg(nil), b.history[chatID]...)
	b.mu.Unlock()

	msgs := []ChatMsg{{Role: "system", Content: sys}}
	msgs = append(msgs, hist...)
	msgs = append(msgs, ChatMsg{Role: "user", Content: user})

	b.logf("Запрос от %s в чате %d: %s", m.Sender.DisplayName(), chatID, shorten(text, 80))
	start := time.Now()
	out, err := b.askPrimary(ctx, cfg, msgs)
	if err != nil {
		b.logf("Ошибка основной нейросети: %v", err)
		if !cfg.FallbackEnabled {
			reply("Не получилось получить ответ от нейросети. Попробуйте позже.")
			return
		}
		fb, ferr := newFallbackLLM(cfg, b.ru, b.def)
		if ferr != nil {
			b.logf("Запасная нейросеть не настроена: %v", ferr)
			reply("Не получилось получить ответ от нейросети. Попробуйте позже.")
			return
		}
		fmsgs := msgs
		if cfg.FallbackProvider == "" || cfg.FallbackProvider == "openai" {
			reply("Основная нейросеть сейчас не отвечает — отвечает запасная на нашем компьютере, это может занять пару минут.")
			// Маленькой локальной модели даём только последний обмен репликами: так быстрее.
			fmsgs = []ChatMsg{{Role: "system", Content: sys}}
			if len(hist) >= 2 {
				fmsgs = append(fmsgs, hist[len(hist)-2:]...)
			}
			fmsgs = append(fmsgs, ChatMsg{Role: "user", Content: user})
		}
		fctx, cancel := context.WithTimeout(ctx, 15*time.Minute)
		defer cancel()
		out, err = fb.Chat(fctx, fmsgs, cfg.Temperature, cfg.FallbackMaxTokens)
		if err != nil {
			b.logf("Ошибка запасной нейросети: %v", err)
			reply("Запасная нейросеть тоже не ответила. Попробуйте позже.")
			return
		}
		b.logf("Ответила запасная нейросеть")
	}
	out = strings.TrimSpace(out)
	if out == "" {
		out = "(нейросеть вернула пустой ответ)"
	}
	b.logf("Ответ за %.1f с, %d символов", time.Since(start).Seconds(), len([]rune(out)))
	reply(out)

	n := cfg.HistoryLen
	if n > 0 {
		b.mu.Lock()
		h := append(b.history[chatID], ChatMsg{Role: "user", Content: user}, ChatMsg{Role: "assistant", Content: out})
		if len(h) > n*2 {
			h = h[len(h)-n*2:]
		}
		b.history[chatID] = h
		b.mu.Unlock()
	}
}

func (b *Bot) askPrimary(ctx context.Context, cfg Config, msgs []ChatMsg) (string, error) {
	llm, err := newLLM(cfg, b.ru, b.def)
	if err != nil {
		return "", err
	}
	timeout := 3 * time.Minute
	if cfg.FallbackEnabled && cfg.PrimaryTimeoutSec > 0 {
		timeout = time.Duration(cfg.PrimaryTimeoutSec) * time.Second
	}
	cctx, cancel := context.WithTimeout(ctx, timeout)
	defer cancel()
	return llm.Chat(cctx, msgs, cfg.Temperature, cfg.MaxTokens)
}

func (b *Bot) hintDue(chatID int64) bool {
	b.mu.Lock()
	defer b.mu.Unlock()
	if b.lastHint == nil {
		b.lastHint = map[int64]time.Time{}
	}
	if time.Since(b.lastHint[chatID]) < 10*time.Minute {
		return false
	}
	b.lastHint[chatID] = time.Now()
	return true
}

// rollDay сбрасывает счётчики в полночь. Вызывать под b.mu.
func (b *Bot) rollDay() {
	day := time.Now().Format("2006-01-02")
	if b.usageDay != day {
		b.usageDay = day
		b.usage = map[int64]int{}
		b.total = 0
	}
}

func (b *Bot) takeQuota(cfg Config, userID int64) (bool, string) {
	b.mu.Lock()
	defer b.mu.Unlock()
	b.rollDay()
	if cfg.DailyLimitTotal > 0 && b.total >= cfg.DailyLimitTotal {
		return false, "Общий дневной лимит запросов исчерпан. Попробуйте завтра."
	}
	if cfg.DailyLimitPerUser > 0 && b.usage[userID] >= cfg.DailyLimitPerUser {
		return false, "Ваш дневной лимит запросов исчерпан. Попробуйте завтра."
	}
	b.usage[userID]++
	b.total++
	return true, ""
}

func helpText(cfg Config) string {
	var sb strings.Builder
	sb.WriteString("Я помощник бригады. Пишите вопрос обычным текстом")
	if len(cfg.GroupTriggers) > 0 {
		sb.WriteString(fmt.Sprintf(" (в общем чате начните со слова «%s» или ответьте на моё сообщение)", cfg.GroupTriggers[0]))
	}
	sb.WriteString(".\n\nКоманды:\n")
	for _, c := range cfg.Commands {
		if c.Alias != "" {
			sb.WriteString(fmt.Sprintf("/%s или /%s — %s\n", c.Alias, c.Name, c.Title))
		} else {
			sb.WriteString(fmt.Sprintf("/%s — %s\n", c.Name, c.Title))
		}
	}
	sb.WriteString("\nТабель:\n/пришёл [объект] — отметить приход\n/ушёл — отметить уход, бот посчитает часы\n/часы 8 [объект] — внести часы вручную (за вчера: /часы 8 вчера)\n/табель — часы за месяц (/табель прошлый — за прошлый)\n\n")
	sb.WriteString("/сброс — забыть разговор\n/лимит — сколько запросов осталось\n/id — узнать свой ID")
	return sb.String()
}

// menuCommands — список для меню «/» в Максе. Имена латиницей: так их точно примет любой клиент.
func menuCommands(cfg Config) []BotCommand {
	out := []BotCommand{
		{Name: "help", Description: "Что умеет бот"},
		{Name: "id", Description: "Узнать свой ID и ID чата"},
	}
	for _, c := range cfg.Commands {
		name := c.Alias
		if name == "" {
			name = c.Name
		}
		d := c.Title
		if d == "" {
			d = name
		}
		out = append(out, BotCommand{Name: name, Description: d})
	}
	out = append(out,
		BotCommand{Name: "prishel", Description: "Табель: пришёл на работу"},
		BotCommand{Name: "ushel", Description: "Табель: ушёл, посчитать часы"},
		BotCommand{Name: "chasy", Description: "Табель: внести часы, например /chasy 8"},
		BotCommand{Name: "tabel", Description: "Табель за месяц"})
	out = append(out, BotCommand{Name: "reset", Description: "Забыть разговор"}, BotCommand{Name: "limit", Description: "Сколько запросов осталось"})
	if len(out) > 32 {
		out = out[:32]
	}
	return out
}

func matchCommand(cmds []Command, text string) (Command, string, bool) {
	if !strings.HasPrefix(text, "/") {
		return Command{}, "", false
	}
	w := firstWord(text)
	name := strings.ToLower(strings.TrimPrefix(w, "/"))
	if i := strings.Index(name, "@"); i >= 0 {
		name = name[:i]
	}
	for _, c := range cmds {
		if strings.ToLower(c.Name) == name || (c.Alias != "" && strings.ToLower(c.Alias) == name) {
			return c, strings.TrimSpace(text[len(w):]), true
		}
	}
	return Command{}, "", false
}

func firstWord(s string) string {
	s = strings.TrimSpace(s)
	if i := strings.IndexFunc(s, unicode.IsSpace); i >= 0 {
		return s[:i]
	}
	return s
}

// cutPrefixWord: «бот, посчитай» → «посчитай». Совпадение без учёта регистра и только целым словом.
func cutPrefixWord(text, word string) (string, bool) {
	tr, wr := []rune(text), []rune(word)
	if len(tr) < len(wr) || !strings.EqualFold(string(tr[:len(wr)]), word) {
		return "", false
	}
	rest := tr[len(wr):]
	if len(rest) > 0 && (unicode.IsLetter(rest[0]) || unicode.IsDigit(rest[0])) {
		return "", false
	}
	return strings.TrimLeft(string(rest), " ,.:;!—-\t\n"), true
}

func replaceFold(s, old, repl string) string {
	i := strings.Index(strings.ToLower(s), strings.ToLower(old))
	if i < 0 {
		return s
	}
	return s[:i] + repl + s[i+len(old):]
}

func shorten(s string, n int) string {
	r := []rune(strings.ReplaceAll(s, "\n", " "))
	if len(r) <= n {
		return string(r)
	}
	return string(r[:n]) + "…"
}
