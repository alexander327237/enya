#!/bin/sh
# Установка бота бригады на Debian/Ubuntu/Mint.
#   wget -qO- <адрес>/install.sh | sh            — только бот
#   wget -qO- <адрес>/install.sh | sh -s ollama  — бот + локальная запасная нейросеть
set -e

BASE="https://raw.githubusercontent.com/alexander327237/enya/claude/plumbing-brigade-automation-ybz9lt"
DIR="$HOME/brigade-bot"
MODEL="${MODEL:-qwen2.5:3b}"

fetch() { # fetch URL FILE
	if command -v curl >/dev/null 2>&1; then curl -fL --retry 3 "$1" -o "$2"; else wget -q --tries=3 "$1" -O "$2"; fi
}

echo "== Скачиваю бота в $DIR"
mkdir -p "$DIR"
fetch "$BASE/dist/brigade-bot-linux-amd64" "$DIR/brigade-bot.new"
chmod +x "$DIR/brigade-bot.new"
mv -f "$DIR/brigade-bot.new" "$DIR/brigade-bot"
fetch "$BASE/brigade-bot/logo.png" "$DIR/logo.png" || true

echo "== Настраиваю автозапуск"
mkdir -p "$HOME/.config/systemd/user"
cat > "$HOME/.config/systemd/user/brigade-bot.service" <<EOF
[Unit]
Description=Бот бригады (Макс)
After=network-online.target

[Service]
ExecStart=$DIR/brigade-bot -no-browser
WorkingDirectory=$DIR
Restart=always
RestartSec=5

[Install]
WantedBy=default.target
EOF
systemctl --user daemon-reload
systemctl --user enable brigade-bot >/dev/null
systemctl --user restart brigade-bot
# Чтобы бот стартовал при включении ноутбука, даже без входа в систему.
sudo loginctl enable-linger "$USER" || echo "!! Не удалось включить запуск без входа — бот стартует после входа в систему."

echo "== Ярлык «Бот бригады»"
cat > "$DIR/brigade-bot.desktop" <<EOF
[Desktop Entry]
Type=Application
Name=Бот бригады
Comment=Панель управления ботом
Exec=xdg-open http://127.0.0.1:8765
Icon=$DIR/logo.png
Terminal=false
Categories=Network;
EOF
mkdir -p "$HOME/.local/share/applications"
cp "$DIR/brigade-bot.desktop" "$HOME/.local/share/applications/"
DESK="$(xdg-user-dir DESKTOP 2>/dev/null || echo "$HOME/Desktop")"
if [ -d "$DESK" ]; then
	cp "$DIR/brigade-bot.desktop" "$DESK/"
	chmod +x "$DESK/brigade-bot.desktop"
fi

if [ "$1" = "ollama" ]; then
	echo "== Устанавливаю Ollama и модель $MODEL (несколько ГБ, может занять время)"
	if ! command -v ollama >/dev/null 2>&1; then
		if command -v curl >/dev/null 2>&1; then curl -fsSL https://ollama.com/install.sh | sh; else wget -qO- https://ollama.com/install.sh | sh; fi
	fi
	ollama pull "$MODEL"
	echo "== Запасная нейросеть: в панели → «Нейросеть» → «Запасная» включите галочку, модель: $MODEL"
fi

echo
echo "Готово. Панель: http://127.0.0.1:8765 (ярлык «Бот бригады» на рабочем столе и в меню)."
echo "Статус:     systemctl --user status brigade-bot"
echo "Журнал:     journalctl --user -u brigade-bot -f"
echo "Обновить:   запустите эту же команду установки ещё раз."
