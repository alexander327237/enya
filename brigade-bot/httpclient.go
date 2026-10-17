package main

import (
	"crypto/tls"
	"crypto/x509"
	"embed"
	"net/http"
	"time"
)

// Сертификаты Минцифры: их требуют API Макса и GigaChat, а в Windows они обычно не установлены.
// Используются только клиентом для российских сервисов, остальные запросы идут с системными сертификатами.
//
//go:embed certs/*.crt
var certFS embed.FS

func newRuClient() *http.Client {
	pool, err := x509.SystemCertPool()
	if err != nil || pool == nil {
		pool = x509.NewCertPool()
	}
	entries, _ := certFS.ReadDir("certs")
	for _, e := range entries {
		if b, err := certFS.ReadFile("certs/" + e.Name()); err == nil {
			pool.AppendCertsFromPEM(b)
		}
	}
	tr := http.DefaultTransport.(*http.Transport).Clone()
	tr.TLSClientConfig = &tls.Config{RootCAs: pool, MinVersion: tls.VersionTLS12}
	return &http.Client{Transport: tr, Timeout: 120 * time.Second}
}

func newDefaultClient() *http.Client {
	return &http.Client{Timeout: 180 * time.Second}
}
