# 把 carddetection.onnx 传到 GitHub Release(编译用)

矫正模型 `carddetection.onnx` 有 **37.8MB**,GitHub 网页"上传文件"单文件上限 25MB,传不进仓库。
所以放到 **Release 附件**里(附件单个可到 2GB),编译时 build.yml 会自动下载到 `assets/models/`。

> 这个模型是旧App ffdagdfc 训练好的原始文件,**不可替换**。build.yml 下载后会用 sha256 校验,
> 对不上会直接让编译失败,保证用的就是这个原始模型。

---

## 第一步:拿到正确的模型文件

用旧App里那一个,别从别处找同名的。路径:

```
救援系统/android-A/ffdagdfc/assets/models/carddetection.onnx
```

- 文件名(必须一模一样):`carddetection.onnx`
- 大小:39,626,866 字节(约 37.8MB)
- sha256:`ca99ed5a20c11cf1c30020a1f722fcc85f020e5cea3ce629ffb576da981bb321`

（想核对的话:Windows 里 `certutil -hashfile carddetection.onnx SHA256`，结果应等于上面这串。）

---

## 第二步:在 GitHub 建一个 Release，tag 必须叫 models

1. 打开你的仓库网页 → 右侧 **Releases** →（或顶部 **Releases** 链接）→ 点 **Draft a new release**（草拟新发布）。
2. **Choose a tag** 处，**手动输入** `models` 然后点 **Create new tag: models on publish**。
   - ⚠️ tag 名必须正好是 `models`（小写、无空格)。build.yml 就是按这个 tag 找的。
3. Release 标题随便填(例如 `models`)。
4. 把 `carddetection.onnx` **拖到** "Attach binaries by dropping them here" 那块区域，等它上传完(进度条到 100%)。
   - ⚠️ 上传后附件名必须是 `carddetection.onnx`（拖原文件即可，别改名)。
5. 点 **Publish release**。

做完后,这个 Release 里应该能看到一个附件:`carddetection.onnx`(37.8MB)。

---

## 第三步:把代码传上去 / 触发编译

- 把 `SendImageNew` 工程(含本次新增的 `DocScannerCorrection.java`、`libdocscanner-correction.so`、改过的 `build.yml` 等)照旧用网页传进仓库。
  - 注意:仓库里**不要**放 `carddetection.onnx`（.gitignore 已忽略它,它走 Release）。
  - `libdocscanner-correction.so`（0.95MB）和 `unetv2.onnx`（1.56MB）这些小文件照常进仓库。
- 传完后去 **Actions** 页,会自动跑 `Build APK`;或手动点 **Run workflow**。

---

## build.yml 里的对应关系(它怎么找到模型)

编译时有一步「下载矫正模型」,命令是:

```bash
gh release download models --repo "$GITHUB_REPOSITORY" --pattern 'carddetection.onnx' --dir assets/models --clobber
```

- `models` = 你建的 Release 的 **tag**（第二步那个,必须一致）。
- `carddetection.onnx` = 你上传的**附件文件名**（必须一致）。
- 下载后立刻做 sha256 校验,等于 `ca99ed5a…` 才继续,否则编译中止。

所以只要 **tag=models、附件名=carddetection.onnx、文件是旧App原始那个**,编译就能拿到模型,APK 里就有矫正能力。

---

## 出错排查

| 编译报错 | 原因 | 解决 |
|---|---|---|
| `release not found` / 下载失败 | Release 的 tag 不是 `models` | 把 Release 的 tag 改成 `models`（或重建) |
| `carddetection.onnx: No such file` | 附件名不对 | 附件必须叫 `carddetection.onnx` |
| `sha256sum: WARNING ... FAILED` | 传的不是旧App原始模型,或文件损坏 | 重新用 ffdagdfc 里那个原始文件上传 |

模型只需传一次,以后改代码重编不用再传(Release 一直在)。换了模型才需要重新传 + 更新 build.yml 里的 sha256。
