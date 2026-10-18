package main

import (
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
	"sync"
)

type Person struct {
	ID   int64  `json:"id"`
	Name string `json:"name"`
}

type Command struct {
	Name   string `json:"name"`
	Alias  string `json:"alias"` // латинское имя для меню команд в Максе
	Title  string `json:"title"`
	Prompt string `json:"prompt"`
}

type GigaChatCfg struct {
	AuthKey string `json:"auth_key"`
	Scope   string `json:"scope"`
	Model   string `json:"model"`
}

type YandexCfg struct {
	APIKey   string `json:"api_key"`
	FolderID string `json:"folder_id"`
	Model    string `json:"model"`
}

type OpenAICfg struct {
	BaseURL string `json:"base_url"`
	APIKey  string `json:"api_key"`
	Model   string `json:"model"`
}

type Config struct {
	MaxToken  string `json:"max_token"`
	AutoStart bool   `json:"autostart"`

	Provider string      `json:"provider"` // gigachat | yandex | openai
	GigaChat GigaChatCfg `json:"gigachat"`
	Yandex   YandexCfg   `json:"yandex"`
	OpenAI   OpenAICfg   `json:"openai"`

	// Запасная нейросеть (обычно локальная Ollama) — если основная не ответила.
	FallbackEnabled   bool      `json:"fallback_enabled"`
	Fallback          OpenAICfg `json:"fallback"`
	FallbackMaxTokens int       `json:"fallback_max_tokens"`
	PrimaryTimeoutSec int       `json:"primary_timeout_sec"`

	SystemPrompt string    `json:"system_prompt"`
	Commands     []Command `json:"commands"`
	Temperature  float64   `json:"temperature"`
	MaxTokens    int       `json:"max_tokens"`
	HistoryLen   int       `json:"history_len"`

	// Доступ: бот отвечает только разрешённым людям. В группах — только в разрешённых чатах.
	AllowedUsers []Person `json:"allowed_users"`
	AllowedChats []Person `json:"allowed_chats"`
	// Если true — в разрешённом чате бот отвечает всем участникам, а не только разрешённым людям.
	OpenInAllowedChats bool `json:"open_in_allowed_chats"`
	// Слова, с которых должно начинаться сообщение в группе, чтобы бот ответил.
	GroupTriggers []string `json:"group_triggers"`

	DailyLimitPerUser int `json:"daily_limit_per_user"`
	DailyLimitTotal   int `json:"daily_limit_total"`
}

func defaultConfig() Config {
	return Config{
		AutoStart: true,
		Provider:  "openai",
		GigaChat:  GigaChatCfg{Scope: "GIGACHAT_API_PERS", Model: "GigaChat"},
		Yandex:    YandexCfg{Model: "yandexgpt-lite"},
		OpenAI:    OpenAICfg{},

		Fallback:          OpenAICfg{BaseURL: "http://localhost:11434/v1", Model: "qwen2.5:3b"},
		FallbackMaxTokens: 400,
		PrimaryTimeoutSec: 60,
		SystemPrompt: "Ты — помощник бригады сварщиков-сантехников (отопление, водоснабжение, канализация, сварка трубопроводов), " +
			"работающей в Москве и Московской области как ИП. Отвечай по-русски, коротко и по делу, без воды. " +
			"Если не уверен в цифрах (цены, нормы, СП, ГОСТ) — так и скажи и предложи, где проверить. " +
			"Не выдумывай номера нормативов и цены.",
		Commands: []Command{
			{Name: "смета", Alias: "smeta", Title: "Черновик сметы", Prompt: "Составь черновик сметы по описанию работ: таблица «работа / ед. / кол-во / цена / сумма», отдельно материалы. Цены — ориентировочные по Москве, помечай их как ориентир. В конце — итог и что уточнить на объекте."},
			{Name: "материалы", Alias: "materialy", Title: "Список материалов", Prompt: "Составь список материалов и расходников для описанных работ: наименование, размер/Ду, количество с запасом 10%. Сгруппируй: трубы, фитинги, арматура, крепёж, расходники для сварки."},
			{Name: "акт", Alias: "akt", Title: "Текст акта", Prompt: "Подготовь текст акта выполненных работ по описанию: таблица работ (наименование, ед., кол-во, цена, сумма), итог прописью, «НДС не облагается (УСН)». Оставь поля для реквизитов пустыми."},
			{Name: "тендер", Alias: "tender", Title: "Разбор закупки", Prompt: "Разбери текст закупки для бригады-ИП без опыта госконтрактов: предмет и объём, цена, сроки, чей материал, обеспечение, требования к участнику (опыт, СРО, лицензии, НАКС). Перечисли красные флаги (нереальные сроки, оплата после документов третьих лиц, подача за часы, документы на персонал за 1 день). Итог: брать / не брать / уточнить."},
			{Name: "клиенту", Alias: "klient", Title: "Сообщение клиенту", Prompt: "Напиши вежливое деловое сообщение клиенту или заказчику по описанию. Коротко, без канцелярита."},
		},
		Temperature:       0.3,
		MaxTokens:         1500,
		HistoryLen:        10,
		GroupTriggers:     []string{"бот", "/ai"},
		DailyLimitPerUser: 50,
		DailyLimitTotal:   300,
	}
}

type Store struct {
	mu   sync.RWMutex
	path string
	cfg  Config
}

func configPath() string {
	exe, err := os.Executable()
	if err != nil {
		return "brigade-bot-config.json"
	}
	return filepath.Join(filepath.Dir(exe), "brigade-bot-config.json")
}

func LoadStore(path string) (*Store, error) {
	s := &Store{path: path, cfg: defaultConfig()}
	b, err := os.ReadFile(path)
	if errors.Is(err, os.ErrNotExist) {
		return s, s.save()
	}
	if err != nil {
		return nil, err
	}
	if err := json.Unmarshal(b, &s.cfg); err != nil {
		return nil, err
	}
	return s, nil
}

func (s *Store) Get() Config {
	s.mu.RLock()
	defer s.mu.RUnlock()
	c := s.cfg
	c.AllowedUsers = append([]Person(nil), s.cfg.AllowedUsers...)
	c.AllowedChats = append([]Person(nil), s.cfg.AllowedChats...)
	c.Commands = append([]Command(nil), s.cfg.Commands...)
	c.GroupTriggers = append([]string(nil), s.cfg.GroupTriggers...)
	return c
}

func (s *Store) Update(fn func(c *Config)) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	fn(&s.cfg)
	return s.save()
}

func (s *Store) save() error {
	b, err := json.MarshalIndent(s.cfg, "", "  ")
	if err != nil {
		return err
	}
	tmp := s.path + ".tmp"
	if err := os.WriteFile(tmp, b, 0o600); err != nil {
		return err
	}
	return os.Rename(tmp, s.path)
}

func hasPerson(list []Person, id int64) bool {
	for _, p := range list {
		if p.ID == id {
			return true
		}
	}
	return false
}
