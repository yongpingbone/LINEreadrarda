# LINE Radar

LINE Radar 是一個 Android 本機監控實驗專案，用來偵測**使用者自己手機、自己登入的 LINE 聊天畫面**中的已讀狀態與新訊息變化。

目前 v0.6 開始把背景模式改成 **非 Root Shizuku + Virtual Display + Accessibility** 架構，目標是讓 LINE 留在第二 Display 持續運作，而使用者可以在主畫面正常使用其他 App，不需要懸浮視窗。

> 本專案不是 LINE 官方產品，與 LY Corporation / LINE 無關。

## 目前功能

- 最多 5 位監控對象
- 每位對象可獨立開關：
  - 已讀提醒
  - 新訊息提醒
  - Android 通知
  - 震動
  - 保存訊息正文到本機歷史
  - 背景持續監控
- 第一次進入指定聊天室時自動建立監控基準
- 多語系已讀文字辨識：繁中、簡中、英文、日文、韓文常見 UI
- Accessibility 主動 watchdog，不完全依賴 LINE 是否送出下一個 accessibility event
- Shizuku 非 Root 背景模式
- Virtual Display 狀態與 LINE 第二 Display 驗證
- No-Read 實驗層（僅實驗／側載版本）

## 一般使用者最短流程

1. 安裝 LINE Radar。
2. 開啟通知權限。
3. Radar 會在需要時說明 Accessibility 會讀取什麼，使用者同意後再前往 Android 無障礙設定。
4. 新增 LINE 聊天室顯示名稱。
5. Radar 自動開啟 LINE，進入指定聊天室停留約 1–2 秒，建立基準。
6. 需要背景持續監控時，直接打開人物卡的「背景持續監控」。
7. Radar 會自動檢查 Shizuku：
   - 沒安裝 → 提供 Google Play / GitHub 安裝入口
   - 已安裝但沒啟動 → 顯示 Wireless debugging 啟動教學
   - 已啟動但沒授權 → 直接要求 Shizuku 授權
   - 全部完成 → 自動建立第二 Display、把 LINE 啟動到第二 Display、等待 Accessibility 驗證
8. 只有顯示「LINE 已驗證」才代表背景模式真正成功。

完整圖文式操作邏輯請看 [`docs/USER_GUIDE.md`](docs/USER_GUIDE.md)。

## 為什麼使用 Shizuku？

舊的 v0.5 MediaProjection 第二 Display 在 Android 鎖屏時可能被系統終止，因此第二 Display 也會一起消失，導致息屏後收不到已讀通知。

v0.6 改成 Shizuku 非 Root 路線：

- Android 11+ 可使用 Wireless debugging 啟動 Shizuku，不需要 Root。
- Radar 透過使用者明確授權後的 Shizuku shell 身分，把 LINE 啟動到指定 Virtual Display。
- 第二 Display 不再綁定 MediaProjection 的螢幕錄製 session。

Shizuku 官方下載：

- Google Play: `moe.shizuku.privileged.api`
- GitHub Releases: https://github.com/RikkaApps/Shizuku/releases

> 非 Root Shizuku 在手機重新開機後通常需要重新啟動。Radar 會檢查狀態，不會把「Shizuku 已停止」誤顯示成監控正常。

## 背景模式成功標準

背景模式不是「Virtual Display 建立成功」就算成功，必須同時滿足：

1. Accessibility Service 已啟用
2. Shizuku 已安裝
3. Shizuku service 已啟動
4. LINE Radar 已取得 Shizuku 授權
5. 第二 Virtual Display 存在
6. LINE 已被啟動到第二 Display
7. Accessibility 在第二 Display 確實看得到 LINE window
8. 指定聊天室基準已建立

只有上述條件成立，UI / 常駐通知才應顯示「背景持續監控」。

## 已讀時間的定義

Radar 顯示的是：

- **首次確認時間**：Radar 第一次看到新已讀狀態的時間
- **可能已讀區間**：上一次確認未讀，到本次確認已讀之間的區間

這不是 LINE 伺服器提供的精確已讀時間。

## 訊息隱私

- Radar 不需要 INTERNET permission 來執行核心監控。
- 訊息判斷與歷史都在本機完成。
- 通知可以顯示最新訊息文字。
- 每個監控對象的「保存訊息內容」預設可關閉；關閉時歷史只記錄事件，不永久保存正文。
- Accessibility 資料用途必須在 App 內明確揭露並取得使用者同意。

更多內容：[`docs/PRIVACY.md`](docs/PRIVACY.md)。

## 架構

目前主要元件：

- `MainActivity`：人物卡、設定、Shizuku onboarding
- `LineReadAccessibilityService`：讀取 LINE UI、已讀／新訊息判斷、第二 Display 驗證
- `ProjectionForegroundService`：名稱暫時保留，v0.6 已改為 Shizuku Virtual Display 常駐服務，不再使用 MediaProjection
- `VirtualDisplayEngine`：建立／維持第二 Display
- `ShizukuBridge`：檢查、授權與 shell bridge
- `Prefs`：本機狀態與每位人物設定
- `NoReadXposedHook`：實驗 No-Read 層，與正式 Play 發行版必須分離

詳細設計請看 [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md)。

## 未來多平台方向

LINE 只是第一個 adapter。未來規劃把平台差異隔離成：

```text
Radar Core
├─ PlatformAdapter: LINE
├─ PlatformAdapter: Instagram
├─ PlatformAdapter: Threads
└─ PlatformAdapter: Messenger
```

核心只負責：監控對象、事件、通知、歷史、背景 session、權限與 UI。
平台 adapter 自己負責：package、聊天室辨識、read receipt 規則、message parser、automation selectors。

詳見 [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md)。

## Google Play 上架注意事項

目前實驗 APK **不能直接視為 Play-ready**。至少需要：

- Accessibility API declaration
- App 內醒目揭露與明確同意
- Privacy Policy / Data Safety
- 將 No-Read / Xposed hook 從 Play flavor 完全移除
- Play flavor 不應包含 Xposed metadata 或 hook entry point
- 重新檢查 Foreground Service、Accessibility、Shizuku 依賴與商店文案
- 提供審核影片，清楚展示 Accessibility consent 與核心用途

完整清單：[`docs/PLAY_STORE_READINESS.md`](docs/PLAY_STORE_READINESS.md)。

## 開發分支

v0.6 非 Root Shizuku 實驗線：

```text
exp/v0.6-shizuku-noroot
```

在實機驗證「主螢幕息屏後，第二 Display + LINE Accessibility 仍持續可讀」之前，不合併到正式穩定線。

## 安全邊界

LINE Radar 只針對使用者自己登入、自己裝置上的 UI。專案不提供：

- 他人裝置監控
- LINE 帳密取得
- 驗證繞過
- 遠端間諜功能
- 讀取其他 App 私有 `/data/user/0/...` 資料

## License / Third-party

Shizuku API 為第三方開源依賴。正式發布前應在 App 與 repository 補齊第三方授權與 NOTICE 清單。
