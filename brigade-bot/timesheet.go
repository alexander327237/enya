package main

import (
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"
)

// Табель: отметки «пришёл / ушёл» и часы, внесённые вручную. Считает программа, не нейросеть.

type TSEntry struct {
	ID     int64   `json:"id"`
	UserID int64   `json:"user_id"`
	Name   string  `json:"name"`
	Date   string  `json:"date"`  // 2026-10-18
	Start  string  `json:"start"` // 08:05, пусто при ручном вводе
	End    string  `json:"end"`
	Hours  float64 `json:"hours"`
	Object string  `json:"object"`
	Open   bool    `json:"open"` // пришёл, но ещё не ушёл
	// Время прихода целиком — чтобы правильно посчитать смену через полночь.
	StartAt time.Time `json:"start_at,omitempty"`
}

type Timesheet struct {
	mu      sync.Mutex
	path    string    // пусто — только в памяти (тесты)
	Entries []TSEntry `json:"entries"`
	NextID  int64     `json:"next_id"`
}

func LoadTimesheet(path string) (*Timesheet, error) {
	ts := &Timesheet{path: path, NextID: 1}
	if path == "" {
		return ts, nil
	}
	b, err := os.ReadFile(path)
	if errors.Is(err, os.ErrNotExist) {
		return ts, nil
	}
	if err != nil {
		return nil, err
	}
	if err := json.Unmarshal(b, ts); err != nil {
		return nil, err
	}
	return ts, nil
}

// save вызывать под ts.mu.
func (ts *Timesheet) save() error {
	if ts.path == "" {
		return nil
	}
	b, err := json.MarshalIndent(ts, "", "  ")
	if err != nil {
		return err
	}
	tmp := ts.path + ".tmp"
	if err := os.WriteFile(tmp, b, 0o600); err != nil {
		return err
	}
	return os.Rename(tmp, ts.path)
}

func roundHours(h float64) float64 { return float64(int(h*100+0.5)) / 100 }

func (ts *Timesheet) openFor(userID int64) int {
	for i := range ts.Entries {
		if ts.Entries[i].UserID == userID && ts.Entries[i].Open {
			return i
		}
	}
	return -1
}

func (ts *Timesheet) CheckIn(userID int64, name, object string, now time.Time) (TSEntry, bool, error) {
	ts.mu.Lock()
	defer ts.mu.Unlock()
	if i := ts.openFor(userID); i >= 0 {
		return ts.Entries[i], false, nil
	}
	e := TSEntry{ID: ts.NextID, UserID: userID, Name: name, Date: now.Format("2006-01-02"),
		Start: now.Format("15:04"), Object: object, Open: true, StartAt: now}
	ts.NextID++
	ts.Entries = append(ts.Entries, e)
	return e, true, ts.save()
}

func (ts *Timesheet) CheckOut(userID int64, now time.Time) (TSEntry, bool, error) {
	ts.mu.Lock()
	defer ts.mu.Unlock()
	i := ts.openFor(userID)
	if i < 0 {
		return TSEntry{}, false, nil
	}
	e := &ts.Entries[i]
	e.Open = false
	e.End = now.Format("15:04")
	e.Hours = roundHours(now.Sub(e.StartAt).Hours())
	return *e, true, ts.save()
}

func (ts *Timesheet) Add(e TSEntry) (TSEntry, error) {
	ts.mu.Lock()
	defer ts.mu.Unlock()
	e.ID = ts.NextID
	ts.NextID++
	e.Hours = roundHours(e.Hours)
	ts.Entries = append(ts.Entries, e)
	return e, ts.save()
}

func (ts *Timesheet) Delete(id int64) error {
	ts.mu.Lock()
	defer ts.mu.Unlock()
	out := ts.Entries[:0]
	for _, e := range ts.Entries {
		if e.ID != id {
			out = append(out, e)
		}
	}
	ts.Entries = out
	return ts.save()
}

// Month — записи за месяц «2026-10», по дате и имени.
func (ts *Timesheet) Month(ym string) []TSEntry {
	ts.mu.Lock()
	defer ts.mu.Unlock()
	var out []TSEntry
	for _, e := range ts.Entries {
		if strings.HasPrefix(e.Date, ym) {
			out = append(out, e)
		}
	}
	sort.SliceStable(out, func(i, j int) bool {
		if out[i].Date != out[j].Date {
			return out[i].Date < out[j].Date
		}
		return out[i].Name < out[j].Name
	})
	return out
}

type TSPerson struct {
	Name     string             `json:"name"`
	Hours    float64            `json:"hours"`
	Days     int                `json:"days"`
	ByObject map[string]float64 `json:"by_object"`
}

func Summarize(entries []TSEntry) []TSPerson {
	m := map[string]*TSPerson{}
	days := map[string]map[string]bool{}
	for _, e := range entries {
		if e.Open {
			continue
		}
		p := m[e.Name]
		if p == nil {
			p = &TSPerson{Name: e.Name, ByObject: map[string]float64{}}
			m[e.Name] = p
			days[e.Name] = map[string]bool{}
		}
		p.Hours = roundHours(p.Hours + e.Hours)
		obj := e.Object
		if obj == "" {
			obj = "без объекта"
		}
		p.ByObject[obj] = roundHours(p.ByObject[obj] + e.Hours)
		days[e.Name][e.Date] = true
	}
	var out []TSPerson
	for name, p := range m {
		p.Days = len(days[name])
		out = append(out, *p)
	}
	sort.Slice(out, func(i, j int) bool { return out[i].Name < out[j].Name })
	return out
}

func fmtHours(h float64) string {
	s := strconv.FormatFloat(h, 'f', 2, 64)
	s = strings.TrimRight(strings.TrimRight(s, "0"), ".")
	return strings.ReplaceAll(s, ".", ",")
}

var monthNames = []string{"", "январь", "февраль", "март", "апрель", "май", "июнь", "июль", "август", "сентябрь", "октябрь", "ноябрь", "декабрь"}

func monthTitle(ym string) string {
	t, err := time.Parse("2006-01", ym)
	if err != nil {
		return ym
	}
	return fmt.Sprintf("%s %d", monthNames[t.Month()], t.Year())
}

// tsCommand — команды табеля в чате. ok=false, если это не команда табеля.
func tsCommand(cmd string) (string, bool) {
	switch cmd {
	case "/пришёл", "/пришел", "/prishel", "/in":
		return "in", true
	case "/ушёл", "/ушел", "/ushel", "/out":
		return "out", true
	case "/часы", "/chasy", "/hours":
		return "hours", true
	case "/табель", "/tabel":
		return "report", true
	}
	return "", false
}

func (b *Bot) handleTimesheet(kind, args string, m MaxMessage, now time.Time) string {
	name := m.Sender.DisplayName()
	uid := m.Sender.UserID
	args = strings.TrimSpace(args)
	switch kind {
	case "in":
		e, created, err := b.ts.CheckIn(uid, name, args, now)
		if err != nil {
			return "Не удалось сохранить отметку: " + err.Error()
		}
		if !created {
			return fmt.Sprintf("%s, вы уже отметились сегодня в %s. Когда закончите — /ушёл.", name, e.Start)
		}
		where := ""
		if e.Object != "" {
			where = ", объект: " + e.Object
		}
		return fmt.Sprintf("✅ %s пришёл в %s%s.", name, e.Start, where)
	case "out":
		e, ok, err := b.ts.CheckOut(uid, now)
		if err != nil {
			return "Не удалось сохранить отметку: " + err.Error()
		}
		if !ok {
			return "Вы сегодня не отмечали приход. Напишите /пришёл, или внесите часы: /часы 8"
		}
		return fmt.Sprintf("👋 %s ушёл в %s. Отработано %s ч (с %s).", name, e.End, fmtHours(e.Hours), e.Start)
	case "hours":
		fields := strings.Fields(args)
		if len(fields) == 0 {
			return "Напишите сколько часов, например: /часы 8 или /часы 7,5 Химки. За вчера: /часы 8 вчера"
		}
		h, err := strconv.ParseFloat(strings.ReplaceAll(fields[0], ",", "."), 64)
		if err != nil || h <= 0 || h > 24 {
			return "Не понял число часов. Пример: /часы 8 или /часы 7,5 Химки"
		}
		day := now
		rest := fields[1:]
		if len(rest) > 0 && strings.EqualFold(rest[0], "вчера") {
			day = now.AddDate(0, 0, -1)
			rest = rest[1:]
		}
		e, err := b.ts.Add(TSEntry{UserID: uid, Name: name, Date: day.Format("2006-01-02"), Hours: h, Object: strings.Join(rest, " ")})
		if err != nil {
			return "Не удалось сохранить: " + err.Error()
		}
		return fmt.Sprintf("✅ Записал: %s, %s — %s ч%s.", name, day.Format("02.01"), fmtHours(e.Hours), map[bool]string{true: ", " + e.Object, false: ""}[e.Object != ""])
	case "report":
		ym := now.Format("2006-01")
		if strings.HasPrefix(strings.ToLower(args), "прош") {
			ym = now.AddDate(0, -1, 0).Format("2006-01")
		}
		sum := Summarize(b.ts.Month(ym))
		if len(sum) == 0 {
			return "За " + monthTitle(ym) + " записей нет."
		}
		var sb strings.Builder
		sb.WriteString("Табель за " + monthTitle(ym) + ":\n")
		var total float64
		for _, p := range sum {
			total += p.Hours
			sb.WriteString(fmt.Sprintf("\n%s — %s ч, дней: %d", p.Name, fmtHours(p.Hours), p.Days))
			if len(p.ByObject) > 1 || p.ByObject["без объекта"] == 0 {
				var objs []string
				for o := range p.ByObject {
					objs = append(objs, o)
				}
				sort.Strings(objs)
				for _, o := range objs {
					sb.WriteString(fmt.Sprintf("\n   • %s: %s ч", o, fmtHours(p.ByObject[o])))
				}
			}
		}
		sb.WriteString(fmt.Sprintf("\n\nВсего: %s ч", fmtHours(roundHours(total))))
		return sb.String()
	}
	return ""
}

// CSV для Excel: разделитель «;», дробная часть через запятую, BOM — чтобы Excel понял кириллицу.
func TimesheetCSV(entries []TSEntry) []byte {
	var sb strings.Builder
	sb.WriteString("\ufeffДата;Сотрудник;Объект;Пришёл;Ушёл;Часы\r\n")
	q := func(s string) string { return `"` + strings.ReplaceAll(s, `"`, `""`) + `"` }
	for _, e := range entries {
		if e.Open {
			continue
		}
		d := e.Date
		if t, err := time.Parse("2006-01-02", e.Date); err == nil {
			d = t.Format("02.01.2006")
		}
		sb.WriteString(strings.Join([]string{d, q(e.Name), q(e.Object), e.Start, e.End, fmtHours(e.Hours)}, ";") + "\r\n")
	}
	sb.WriteString("\r\nИтого;;;;;\r\n")
	for _, p := range Summarize(entries) {
		sb.WriteString(fmt.Sprintf("%s;%s;;;;%s\r\n", q(p.Name), fmt.Sprintf("дней: %d", p.Days), fmtHours(p.Hours)))
	}
	return []byte(sb.String())
}
