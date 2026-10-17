package main

import (
	"encoding/json"
	"flag"
	"fmt"
	"net"
	"net/http"
	"os"
	"os/exec"
	"runtime"
	"time"
)

func openBrowser(url string) {
	var cmd *exec.Cmd
	switch runtime.GOOS {
	case "windows":
		cmd = exec.Command("rundll32", "url.dll,FileProtocolHandler", url)
	case "darwin":
		cmd = exec.Command("open", url)
	default:
		cmd = exec.Command("xdg-open", url)
	}
	_ = cmd.Start()
}

func isOurPanel(addr string) bool {
	c := &http.Client{Timeout: 2 * time.Second}
	resp, err := c.Get("http://" + addr + "/api/status")
	if err != nil {
		return false
	}
	defer resp.Body.Close()
	var st map[string]any
	if json.NewDecoder(resp.Body).Decode(&st) != nil {
		return false
	}
	_, ok := st["running"]
	return ok
}

func fatal(format string, a ...any) {
	fmt.Printf(format+"\n", a...)
	if runtime.GOOS == "windows" {
		fmt.Println("\nНажмите Enter, чтобы закрыть окно.")
		_, _ = fmt.Scanln()
	}
	os.Exit(1)
}

func main() {
	port := flag.Int("port", 8765, "порт панели управления")
	noBrowser := flag.Bool("no-browser", false, "не открывать браузер при запуске")
	cfgFile := flag.String("config", configPath(), "файл настроек")
	flag.Parse()

	store, err := LoadStore(*cfgFile)
	if err != nil {
		fatal("Не удалось прочитать настройки %s: %v", *cfgFile, err)
	}
	bot := NewBot(store)
	srv := &Server{store: store, bot: bot}

	addr := fmt.Sprintf("127.0.0.1:%d", *port)
	ln, err := net.Listen("tcp", addr)
	if err != nil {
		// Порт занят. Если это уже наша программа — просто открываем её панель.
		if isOurPanel(addr) {
			fmt.Println("Программа уже запущена. Открываю панель: http://" + addr)
			if !*noBrowser {
				openBrowser("http://" + addr)
			}
			time.Sleep(3 * time.Second)
			return
		}
		// Порт занят чем-то другим — берём соседний свободный.
		for p := *port + 1; p <= *port+20 && err != nil; p++ {
			addr = fmt.Sprintf("127.0.0.1:%d", p)
			ln, err = net.Listen("tcp", addr)
		}
		if err != nil {
			fatal("Не удалось найти свободный порт для панели (%d–%d).", *port, *port+20)
		}
	}
	url := "http://" + addr
	fmt.Println("Бот бригады — панель управления:", url)
	fmt.Println("Настройки хранятся в:", *cfgFile)
	fmt.Println("Не закрывайте это окно, пока бот должен работать.")

	if store.Get().AutoStart && store.Get().MaxToken != "" {
		if err := bot.Start(); err != nil {
			fmt.Println("Автозапуск не удался:", err)
		}
	}
	if !*noBrowser {
		go func() { time.Sleep(500 * time.Millisecond); openBrowser(url) }()
	}
	hs := &http.Server{Handler: srv.routes(), ReadHeaderTimeout: 10 * time.Second}
	if err := hs.Serve(ln); err != nil {
		fatal("Ошибка панели: %v", err)
	}
}
