# LINE Radar 使用教學

本教學以 v0.6 非 Root Shizuku 背景模式為準。

## 1. 第一次安裝

安裝 LINE Radar 後，先允許通知權限。

Radar 在需要 Accessibility 時會先顯示用途說明。使用者同意後才前往 Android 無障礙設定。

Accessibility 可能讀到：

- 聊天室名稱
- 畫面上的已讀文字
- 可見訊息內容

用途只限於本機判斷已讀／新訊息與發出提醒。

## 2. 新增監控對象

1. 打開 LINE Radar。
2. 點「＋ 新增監控對象」。
3. 輸入 LINE 聊天室最上方顯示的名稱。
4. 點「新增並開始」。
5. Radar 會開啟 LINE。
6. 進入指定聊天室並停留約 1–2 秒。
7. 出現「基準已建立」即完成第一次設定。

這一步不需要懸浮視窗，也不需要分割畫面。

## 3. 基準是什麼？

Radar 第一次必須知道「現在已經有哪些已讀／訊息」，才能判斷之後什麼是新的變化。

第一次看到的畫面只會記成起點，不會當成新事件通知。

## 4. 開啟背景持續監控

人物完成基準後，人物卡會出現「背景持續監控」。

直接打開開關即可。Radar 會自動依序檢查：

1. Accessibility 是否已開啟
2. Shizuku 是否已安裝
3. Shizuku service 是否正在運作
4. Radar 是否已取得 Shizuku 授權
5. 第二 Display 是否成功建立
6. LINE 是否成功進入第二 Display
7. Accessibility 是否真的在第二 Display 看到 LINE

使用者不需要先進 Radar Lab。

## 5. 如果沒有 Shizuku

Radar 會跳出安裝視窗。

可選：

- Google Play
- GitHub Releases: https://github.com/RikkaApps/Shizuku/releases

Shizuku 不需要 Root。

## 6. 免 Root 啟動 Shizuku

Android 11+ 可使用 Wireless debugging。

一般流程：

1. 手機設定 → 關於手機 → 軟體資訊
2. 連續點版本號，開啟「開發人員選項」
3. 回到設定 → 開發人員選項
4. 開啟「無線偵錯」
5. 開啟 Shizuku
6. 依 Shizuku 畫面完成無線偵錯配對
7. 按 Shizuku 的「啟動」
8. 回 LINE Radar

Radar 會重新檢查 Shizuku 狀態。

> 手機重新開機後，非 Root Shizuku 通常需要重新啟動。

## 7. Shizuku 授權

當 Shizuku 已啟動，Radar 會說明用途後要求 Shizuku 授權。

Radar 的 Shizuku shell 權限只用於：

- 維持／操作第二 Display 所需的系統橋接
- 把使用者自己的 LINE 啟動到指定 Display

不使用 Root，也不讀取其他 App 的私有資料夾。

## 8. 怎麼知道背景模式真的成功？

不是看到 Display ID 就算成功。

人物卡或診斷頁必須顯示類似：

```text
✓ Display 12 · LINE 已驗證 · 主畫面可自由使用
```

代表：

- 第二 Display 存在
- LINE 已經在第二 Display
- Accessibility 真的看得到 LINE

這時主畫面可以正常使用 IG、Chrome、YouTube、遊戲等 App。

Radar 不需要懸浮視窗。

## 9. 息屏測試

v0.6 不再使用 MediaProjection，所以不應再因為 Android 停止螢幕分享 session 而直接把第二 Display 收掉。

測試方式：

1. 確認人物卡顯示「LINE 已驗證」。
2. 關閉主螢幕。
3. 另一端讀取你之前送出的訊息。
4. 等待 Radar 通知。
5. 再解鎖手機，到 Radar Lab 檢查：
   - Shizuku 是否仍運作
   - 第二 Display 是否仍存在
   - LINE 是否仍驗證成功

如果息屏後其中一項消失，截 Radar Lab 的完整狀態頁回報。

## 10. 新訊息通知

當 LINE Accessibility 提供文字時，通知可以直接顯示最新訊息，例如：

```text
💬 Y麒
等等打給你
```

如果 LINE 沒提供可讀文字，Radar 只會顯示「偵測到聊天室內容更新」，不會自行猜內容。

## 11. 保存訊息正文

每位人物可獨立設定「保存訊息內容到歷史紀錄」。

OFF：

```text
2026/08/26 13:20:00｜Y麒｜💬 新訊息
```

ON：

```text
2026/08/26 13:20:00｜Y麒｜💬 新訊息｜等等打給你
```

通知是否顯示內容，和是否永久保存正文是兩件不同的事。

## 12. 已讀通知

Radar 顯示的是：

- 首次確認時間
- 可能已讀區間

不是 LINE 官方伺服器提供的精確已讀時間。

## 13. 多語系

目前已讀文字包含常見：

- 已讀
- 已读
- Read
- 既読
- 읽음

並盡量使用 UI 結構、座標與 accessibility node 特徵，降低對單一語言的依賴。

## 14. Radar Lab

Radar Lab 現在是進階診斷頁，不是一般操作必要步驟。

可以檢查：

- Shizuku 是否安裝
- Shizuku 是否啟動
- Radar 是否取得 Shizuku 權限
- 第二 Display ID
- Accessibility 是否看到第二 Display 的 LINE

## 15. 常見問題

### Q：一定要 Root 嗎？

不用。v0.6 統一走非 Root Shizuku / Wireless debugging。

### Q：一定要開懸浮視窗嗎？

不用。

### Q：我滑別的 App 時會暫停嗎？

第二 Display 模式成功後，不應因主畫面正在使用其他 App 而自動暫停。

### Q：重新開機後呢？

Radar 設定會保留，但 Shizuku 非 Root service 通常要重新啟動。Radar 應顯示「Shizuku 未啟動」，不會假裝背景監控正常。

### Q：為什麼還有一張常駐通知？

Android 前景服務規則要求長時間背景工作顯示狀態通知。Radar 應只保留必要的一張背景狀態通知；真正的已讀／新訊息才另外通知。

## 16. 回報 Bug 時請提供

- LINE Radar 版本
- Android / One UI 版本
- LINE 版本
- Radar 人物卡畫面
- Radar Lab 完整狀態
- 事件紀錄
- 問題發生前做了哪些步驟
