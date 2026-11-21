# Amuless

Amulet + Limitless = **Amuless**。
LimitlessPendant 用の個人向け Android アプリです。

## omi について

このリポジトリは [BasedHardware/omi](https://github.com/BasedHardware/omi)(MIT License)のフォークで、Android アプリ(`app/`)を改変して使っています。元の README は [README.omi.md](README.omi.md)、ライセンスは [LICENSE](LICENSE) にあります。omi の開発者の皆さんに感謝します。

## 目的

LimitlessPendant のサービスは 2025/12/1 に終了します。omi アプリに移行すると正しく動作しましたが、ペンダントから常時ストリーミングして文字起こしするため、スマホのバッテリー消費が大きくなります。
Amuless は常時ストリーミングをやめ、指定した時刻(深夜)にペンダントの音声データをまとめて取り出して文字起こしします。

## 全体の流れ

1. LimitlessPendant とペアリングする
2. 指定時刻に、ペンダントから音声データを取り出す
3. Tailscale 経由で自宅 MacBook の mlx-whisper に送り、文字起こしする
4. 文字起こしを API キーで自作 Web アプリ「memoria」に保存する
5. 要約は別のツール(muse)が担当する

omi が提供するクラウドサービス(バックエンド、文字起こし、アカウント、課金など)は使いません。

## 開発状況

開発中です。

## 注意

個人開発のため、**Issue と Pull Request は受け付けていません**。
