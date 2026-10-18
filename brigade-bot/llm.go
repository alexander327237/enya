package main

import (
	"bytes"
	"context"
	"crypto/rand"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"sync"
	"time"
)

type ChatMsg struct {
	Role    string `json:"role"`
	Content string `json:"content"`
}

type LLM interface {
	Chat(ctx context.Context, msgs []ChatMsg, temperature float64, maxTokens int) (string, error)
}

func newLLM(c Config, ru, def *http.Client) (LLM, error) {
	switch c.Provider {
	case "gigachat":
		if c.GigaChat.AuthKey == "" {
			return nil, errors.New("не указан ключ авторизации GigaChat")
		}
		return &gigaChat{cfg: c.GigaChat, hc: ru}, nil
	case "yandex":
		if c.Yandex.APIKey == "" || c.Yandex.FolderID == "" {
			return nil, errors.New("не указан API-ключ или ID каталога YandexGPT")
		}
		return &yandexGPT{cfg: c.Yandex, hc: ru}, nil
	case "openai":
		if c.OpenAI.BaseURL == "" || c.OpenAI.Model == "" {
			return nil, errors.New("не указан адрес API или модель")
		}
		return &openAICompat{cfg: c.OpenAI, hc: def}, nil
	}
	return nil, fmt.Errorf("неизвестный провайдер %q", c.Provider)
}

// newFallbackLLM — запасная нейросеть. GigaChat и YandexGPT берут ключи из основных настроек,
// «своя» — из отдельного блока (по умолчанию локальная Ollama).
func newFallbackLLM(c Config, ru, def *http.Client) (LLM, error) {
	if c.FallbackProvider == "" || c.FallbackProvider == "openai" {
		if c.Fallback.BaseURL == "" || c.Fallback.Model == "" {
			return nil, errors.New("у запасной нейросети не указан адрес или модель")
		}
		return &openAICompat{cfg: c.Fallback, hc: def}, nil
	}
	c.Provider = c.FallbackProvider
	return newLLM(c, ru, def)
}

func postJSON(ctx context.Context, hc *http.Client, u string, headers map[string]string, body any, out any) error {
	b, err := json.Marshal(body)
	if err != nil {
		return err
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, u, bytes.NewReader(b))
	if err != nil {
		return err
	}
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Accept", "application/json")
	for k, v := range headers {
		req.Header.Set(k, v)
	}
	return doJSON(hc, req, out)
}

func doJSON(hc *http.Client, req *http.Request, out any) error {
	resp, err := hc.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	data, _ := io.ReadAll(io.LimitReader(resp.Body, 8<<20))
	if resp.StatusCode/100 != 2 {
		msg := strings.TrimSpace(string(data))
		if len(msg) > 400 {
			msg = msg[:400]
		}
		return fmt.Errorf("HTTP %d: %s", resp.StatusCode, msg)
	}
	if out == nil {
		return nil
	}
	if err := json.Unmarshal(data, out); err != nil {
		return fmt.Errorf("не удалось разобрать ответ: %w", err)
	}
	return nil
}

// --- OpenAI-совместимый API (Ollama, LM Studio, OpenRouter, DeepSeek и др.) ---

type openAICompat struct {
	cfg OpenAICfg
	hc  *http.Client
}

type oaResp struct {
	Choices []struct {
		Message ChatMsg `json:"message"`
	} `json:"choices"`
}

func (o *openAICompat) Chat(ctx context.Context, msgs []ChatMsg, temp float64, maxTok int) (string, error) {
	h := map[string]string{}
	if o.cfg.APIKey != "" {
		h["Authorization"] = "Bearer " + o.cfg.APIKey
	}
	body := map[string]any{"model": o.cfg.Model, "messages": msgs, "temperature": temp, "max_tokens": maxTok, "stream": false}
	var r oaResp
	if err := postJSON(ctx, o.hc, strings.TrimRight(o.cfg.BaseURL, "/")+"/chat/completions", h, body, &r); err != nil {
		return "", err
	}
	if len(r.Choices) == 0 {
		return "", errors.New("пустой ответ нейросети")
	}
	return r.Choices[0].Message.Content, nil
}

// --- GigaChat (Сбер) ---

var (
	gigaOAuthURL = "https://ngw.devices.sberbank.ru:9443/api/v2/oauth"
	gigaChatURL  = "https://gigachat.devices.sberbank.ru/api/v1/chat/completions"
)

type gigaChat struct {
	cfg GigaChatCfg
	hc  *http.Client

	mu      sync.Mutex
	token   string
	expires time.Time
}

func uuid4() string {
	var b [16]byte
	_, _ = rand.Read(b[:])
	b[6] = b[6]&0x0f | 0x40
	b[8] = b[8]&0x3f | 0x80
	return fmt.Sprintf("%x-%x-%x-%x-%x", b[0:4], b[4:6], b[6:8], b[8:10], b[10:])
}

func (g *gigaChat) accessToken(ctx context.Context) (string, error) {
	g.mu.Lock()
	defer g.mu.Unlock()
	if g.token != "" && time.Until(g.expires) > time.Minute {
		return g.token, nil
	}
	scope := g.cfg.Scope
	if scope == "" {
		scope = "GIGACHAT_API_PERS"
	}
	form := url.Values{"scope": {scope}}.Encode()
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, gigaOAuthURL, strings.NewReader(form))
	if err != nil {
		return "", err
	}
	key := strings.TrimSpace(g.cfg.AuthKey)
	key = strings.TrimPrefix(key, "Basic ")
	req.Header.Set("Authorization", "Basic "+key)
	req.Header.Set("RqUID", uuid4())
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	req.Header.Set("Accept", "application/json")
	var r struct {
		AccessToken string `json:"access_token"`
		ExpiresAt   int64  `json:"expires_at"`
	}
	if err := doJSON(g.hc, req, &r); err != nil {
		return "", fmt.Errorf("авторизация GigaChat: %w", err)
	}
	g.token = r.AccessToken
	g.expires = time.UnixMilli(r.ExpiresAt)
	if r.ExpiresAt == 0 {
		g.expires = time.Now().Add(25 * time.Minute)
	}
	return g.token, nil
}

func (g *gigaChat) Chat(ctx context.Context, msgs []ChatMsg, temp float64, maxTok int) (string, error) {
	tok, err := g.accessToken(ctx)
	if err != nil {
		return "", err
	}
	model := g.cfg.Model
	if model == "" {
		model = "GigaChat"
	}
	body := map[string]any{"model": model, "messages": msgs, "temperature": temp, "max_tokens": maxTok}
	var r oaResp
	if err := postJSON(ctx, g.hc, gigaChatURL, map[string]string{"Authorization": "Bearer " + tok}, body, &r); err != nil {
		return "", err
	}
	if len(r.Choices) == 0 {
		return "", errors.New("пустой ответ GigaChat")
	}
	return r.Choices[0].Message.Content, nil
}

// --- YandexGPT ---

var yandexURL = "https://llm.api.cloud.yandex.net/foundationModels/v1/completion"

type yandexGPT struct {
	cfg YandexCfg
	hc  *http.Client
}

func (y *yandexGPT) Chat(ctx context.Context, msgs []ChatMsg, temp float64, maxTok int) (string, error) {
	model := y.cfg.Model
	if model == "" {
		model = "yandexgpt-lite"
	}
	uri := model
	if !strings.Contains(model, "://") {
		uri = fmt.Sprintf("gpt://%s/%s/latest", y.cfg.FolderID, model)
	}
	type ym struct {
		Role string `json:"role"`
		Text string `json:"text"`
	}
	var ymsgs []ym
	for _, m := range msgs {
		ymsgs = append(ymsgs, ym{Role: m.Role, Text: m.Content})
	}
	body := map[string]any{
		"modelUri":          uri,
		"completionOptions": map[string]any{"stream": false, "temperature": temp, "maxTokens": strconv.Itoa(maxTok)},
		"messages":          ymsgs,
	}
	h := map[string]string{"Authorization": "Api-Key " + y.cfg.APIKey, "x-folder-id": y.cfg.FolderID}
	var r struct {
		Result struct {
			Alternatives []struct {
				Message struct {
					Text string `json:"text"`
				} `json:"message"`
			} `json:"alternatives"`
		} `json:"result"`
	}
	if err := postJSON(ctx, y.hc, yandexURL, h, body, &r); err != nil {
		return "", err
	}
	if len(r.Result.Alternatives) == 0 {
		return "", errors.New("пустой ответ YandexGPT")
	}
	return r.Result.Alternatives[0].Message.Text, nil
}
