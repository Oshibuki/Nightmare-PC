# 引擎版本档案（ENGINES）

**固化日期：2026-11-25** ｜ 产出工单：Phase 0 / 02 ｜ 决策依据：ADR-0004（自持引擎源码）、ADR-0005（基线 happyyzy fork）

这份文件是引擎侧唯一的版本真相：任何一次"改引擎 → 编译 → 冒烟"都必须落在下面这三个 SHA 上，否则产出的引擎与移动版不是同一份东西。

## 三个精确版本

| 组件 | 仓库 URL | 精确 SHA | 取得方式 |
|---|---|---|---|
| **local-dream**（引擎 wrapper + `dit/build.sh`，参考版本） | `https://github.com/xororz/local-dream.git` | `18be9aa6e8365d6efb9601acf7c13f7147214aa7`<br>（tag `v3.0.0-alpha.4`，**轻量 tag**，直接指向该 commit） | 浅克隆该 tag 后 `git rev-parse HEAD` |
| **stable-diffusion.cpp** | `https://github.com/happyyzy/stable-diffusion.cpp.git` | `35a3d48b23581d2be7c3ad95e92f34662bb5f305` | local-dream 上述 tag 的 **gitlink**：`git ls-tree <tag> app/src/main/cpp/3rdparty/stable-diffusion.cpp` |
| **ggml** | `https://github.com/happyyzy/ggml.git`<br>（该 sdcpp commit 的 `.gitmodules` **实际值**；同一 commit 也存在于上游 `https://github.com/ggml-org/ggml.git`，见下文） | `f8758628270072da26ad44baf5e189d4695d9800` | 上述 sdcpp commit 的 **gitlink**：`git ls-tree <sdcpp-sha> ggml` |

三个 commit 的元数据（供追溯，`git log -1 --format='%ci %s'`）：

| SHA | 提交时间 | 标题 |
|---|---|---|
| `18be9aa6` | 2026-10-03 09:56:23 +0800 | `v3.0.0-alpha.4` |
| `35a3d48b` | 2026-10-02 22:52:33 +0800 | `feat: wire diffusion modulation and refresh benchmarks` |
| `f8758628` | 2026-09-23 21:38:29 +0800 | `hexagon: add split modulation operations` |

## 为什么这三个 SHA 就是移动版实际构建用的

`.github/workflows/dit-engine.yml` 的两个输入默认值把这条链钉死了：

- `local_dream_ref` 默认 `v3.0.0-alpha.4` —— 即上表第一个 SHA。注释写明"Every shipped engine is built on alpha.4"，留空曾导致取到移动过的默认分支、补丁 003 打不上（run 39534075463）。
- `sdcpp_ref` 默认 **空字符串**，其 description 是"blank = local-dream's pinned submodule, which is what you want"。

所以 CI 走的是 `git submodule update --init --recursive --depth 1 -- app/src/main/cpp/3rdparty/stable-diffusion.cpp`，取到的是 **local-dream 该 tag 里固定的 submodule commit**，而不是任何分支的 tip；`--recursive` 再向下取 sdcpp 自己固定的 `ggml`。随后 CI 把两者写进构建摘要（`SDCPP_SHA` / `GGML_SHA`）。

**结论：本档案的两个引擎 SHA 与移动版出货引擎同源。** 只有人为把 `sdcpp_ref` 覆盖成别的 ref 时，那次构建才不再由本文件描述——那样的产物必须另行登记。

> ⚠ 这两个 SHA 都**不是**各自 fork 的当前 HEAD（2026-11-25 实测：`happyyzy/stable-diffusion.cpp` HEAD 为 `1204a2d4…`，`happyyzy/ggml` HEAD 为 `f0ac7d87…`）。"取最新"和"取出货版本"在这里是两个不同的答案，别混。

## 可复现命令

全程只读远端，不需要 GitHub Actions 历史日志，也不需要 GitHub token。

```bash
# 1) local-dream 参考版本（轻量 tag，rev-parse 直接得 commit）
git ls-remote --tags https://github.com/xororz/local-dream.git \
  | grep 'refs/tags/v3.0.0-alpha.4$'
# 期望：18be9aa6e8365d6efb9601acf7c13f7147214aa7	refs/tags/v3.0.0-alpha.4

# 2) 从该 tag 的 tree 读 sdcpp 的 gitlink
git clone --depth 1 --branch v3.0.0-alpha.4 --no-recurse-submodules \
  https://github.com/xororz/local-dream.git local-dream
git -C local-dream ls-tree HEAD app/src/main/cpp/3rdparty/stable-diffusion.cpp
# 期望：160000 commit 35a3d48b23581d2be7c3ad95e92f34662bb5f305	app/src/main/cpp/3rdparty/stable-diffusion.cpp

# 3) 确认该 SHA 真的存在于 happyyzy/stable-diffusion.cpp（按 SHA 取，不取分支）
git init sdcpp && git -C sdcpp fetch --depth 1 \
  https://github.com/happyyzy/stable-diffusion.cpp.git \
  35a3d48b23581d2be7c3ad95e92f34662bb5f305
git -C sdcpp ls-tree FETCH_HEAD ggml
# 期望：160000 commit f8758628270072da26ad44baf5e189d4695d9800	ggml

# 4) 确认该 ggml SHA 存在（两个远端都应答，见下文说明）
git init ggml && git -C ggml fetch --depth 1 \
  https://github.com/happyyzy/ggml.git f8758628270072da26ad44baf5e189d4695d9800
```

**端到端复现（与 CI 完全同一条路径，一步得到两个 SHA）**——这是最有说服力的一条，因为它同时验证了 `.gitmodules` 的 URL 解析：

```bash
cd local-dream
git submodule update --init --recursive --depth 1 -- \
  app/src/main/cpp/3rdparty/stable-diffusion.cpp
cd app/src/main/cpp/3rdparty/stable-diffusion.cpp
echo "SDCPP_SHA=$(git rev-parse HEAD)"      # 35a3d48b23581d2be7c3ad95e92f34662bb5f305
echo "GGML_SHA=$(git -C ggml rev-parse HEAD)" # f8758628270072da26ad44baf5e189d4695d9800
```

## 关于 ggml 的仓库 URL

工单原文写的是"ggml-org/ggml"，但**实测的构建来源是 `happyyzy/ggml`**，这里如实记录：

- sdcpp commit `35a3d48b` 的 `.gitmodules` 写的是 `url = https://github.com/happyyzy/ggml.git`，`git submodule update` 因此从该 fork 取。
- 同一个 commit 对象 `f8758628270072da26ad44baf5e189d4695d9800` **在上游 `ggml-org/ggml` 也存在**（`git fetch --depth 1` 两个远端都成功）。也就是说该版本尚未被 fork 私有提交污染，两种 URL 取到的是同一棵树。

自持（vendor）时用哪个 URL 由工单 07 决定；无论选哪个，**SHA 必须固定为上表的 `f875862827…`**，否则不再与移动版同源。补丁路由（`*.ggml.patch` 打到 `<sdcpp>/ggml`）不受 URL 选择影响。

## 回退值与"非移动版同款构建"

**本次没有用到回退值。** 上面的 SHA 全部来自 git 本身（tag → gitlink → gitlink 逐层 `ls-tree`，再逐个 `fetch` 验证存在），来源是"移动版实际构建所用的固定点"，不是历史 run 日志的转录，因此不存在"历史 run 不可得"这一缺口。

下面两个值**仅供在极端情况下定位"当时 fork 走到了哪"**，明确标注为 **非移动版同款构建**，不得当作上表的替代：

| 组件 | 值（2026-11-25 的 fork HEAD） | 性质 |
|---|---|---|
| `happyyzy/stable-diffusion.cpp` | `1204a2d4cdebe9aae012d1e06a61e5e0a978e1c1` | ⚠ **非移动版同款构建**：比出货版本新，补丁集未针对它验证 |
| `happyyzy/ggml` | `f0ac7d87039573d032db5382e3d211127eac7ea2` | ⚠ **非移动版同款构建**：同上 |

用这两个值构建出的引擎，其行为、ABI 与性能都不可与移动版出货引擎比较；报告里必须写明这一条，不能只报"构建成功"。

## 维护约定

- 新增/升级引擎版本 = 改这张表 + 改工单 07 的 vendor 脚本，两者必须同一次提交，且提交信息里写清为什么升级。
- 补丁集（`backend-patches/`）的适用性**绑定在 sdcpp SHA 上**：`dit/*.patch` 是针对 `35a3d48b` 的树写的，换 SHA 就要重新验证每个补丁仍然打得上、且不是静默 no-op。
- 本文件不记录构建产物哈希（`libdit_engine.so` 的字节数与 sha256 随 NDK/编译器/补丁变化），那些属于每次构建的摘要，不属于版本档案。
