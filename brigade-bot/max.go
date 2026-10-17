package main

import (
	"context"
	"encoding/json"
	"net/http"
	"net/url"
	"strconv"
	"strings"
)

var maxAPIBase = "https://platform-api2.max.ru"

type MaxClient struct {
	token string
	hc    *http.Client
}

type MaxUser struct {
	UserID    int64  `json:"user_id"`
	FirstName string `json:"first_name"`
	LastName  string `json:"last_name"`
	Username  string `json:"username"`
	IsBot     bool   `json:"is_bot"`
}

func (u MaxUser) DisplayName() string {
	n := strings.TrimSpace(u.FirstName + " " + u.LastName)
	if n == "" {
		n = u.Username
	}
	if n == "" {
		n = strconv.FormatInt(u.UserID, 10)
	}
	return n
}

type MaxMessage struct {
	Sender    MaxUser `json:"sender"`
	Recipient struct {
		ChatID   int64  `json:"chat_id"`
		ChatType string `json:"chat_type"`
		UserID   int64  `json:"user_id"`
	} `json:"recipient"`
	Timestamp int64 `json:"timestamp"`
	Link      *struct {
		Type   string   `json:"type"`
		Sender *MaxUser `json:"sender"`
	} `json:"link"`
	Body struct {
		Mid  string `json:"mid"`
		Text string `json:"text"`
	} `json:"body"`
}

type MaxUpdate struct {
	UpdateType string      `json:"update_type"`
	Timestamp  int64       `json:"timestamp"`
	Message    *MaxMessage `json:"message"`
	ChatID     int64       `json:"chat_id"`
	User       *MaxUser    `json:"user"`
	IsChannel  bool        `json:"is_channel"`
}

func (m *MaxClient) req(ctx context.Context, method, path string, q url.Values, body any, out any) error {
	u := maxAPIBase + path
	if len(q) > 0 {
		u += "?" + q.Encode()
	}
	var req *http.Request
	var err error
	if body != nil {
		b, _ := json.Marshal(body)
		req, err = http.NewRequestWithContext(ctx, method, u, strings.NewReader(string(b)))
		if err == nil {
			req.Header.Set("Content-Type", "application/json")
		}
	} else {
		req, err = http.NewRequestWithContext(ctx, method, u, nil)
	}
	if err != nil {
		return err
	}
	req.Header.Set("Authorization", m.token)
	return doJSON(m.hc, req, out)
}

func (m *MaxClient) Me(ctx context.Context) (MaxUser, error) {
	var u MaxUser
	err := m.req(ctx, http.MethodGet, "/me", nil, nil, &u)
	return u, err
}

func (m *MaxClient) Updates(ctx context.Context, marker *int64, timeout int) ([]MaxUpdate, *int64, error) {
	q := url.Values{
		"timeout": {strconv.Itoa(timeout)},
		"limit":   {"100"},
		"types":   {"message_created,bot_added,bot_started"},
	}
	if marker != nil {
		q.Set("marker", strconv.FormatInt(*marker, 10))
	}
	var r struct {
		Updates []MaxUpdate `json:"updates"`
		Marker  *int64      `json:"marker"`
	}
	err := m.req(ctx, http.MethodGet, "/updates", q, nil, &r)
	return r.Updates, r.Marker, err
}

// Send отправляет текст в чат. Длинный текст режется на части (лимит Макса — 4000 символов).
// replyTo — mid сообщения, на которое отвечаем (можно пустым).
func (m *MaxClient) Send(ctx context.Context, chatID int64, text, replyTo string) error {
	parts := splitText(text, 3900)
	for i, p := range parts {
		body := map[string]any{"text": p, "format": "markdown"}
		if i == 0 && replyTo != "" {
			body["link"] = map[string]any{"type": "reply", "mid": replyTo}
		}
		q := url.Values{"chat_id": {strconv.FormatInt(chatID, 10)}}
		err := m.req(ctx, http.MethodPost, "/messages", q, body, nil)
		if err != nil {
			// Повтор без разметки и без ответа — на случай, если сервер не принял markdown или ссылку.
			delete(body, "format")
			delete(body, "link")
			if err2 := m.req(ctx, http.MethodPost, "/messages", q, body, nil); err2 != nil {
				return err
			}
		}
	}
	return nil
}

func splitText(s string, max int) []string {
	r := []rune(s)
	if len(r) <= max {
		return []string{s}
	}
	var out []string
	for len(r) > max {
		cut := max
		for i := max; i > max/2; i-- {
			if r[i] == '\n' {
				cut = i
				break
			}
		}
		out = append(out, string(r[:cut]))
		r = r[cut:]
		for len(r) > 0 && r[0] == '\n' {
			r = r[1:]
		}
	}
	if len(r) > 0 {
		out = append(out, string(r))
	}
	return out
}
