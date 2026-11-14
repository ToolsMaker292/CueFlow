# Pull Request

## 改了什么

<!-- 简要描述用户可见的变化。 -->

## 为什么

<!-- 关联 Issue / 使用场景。 -->

## 检查

- [ ] 没有新增网络权限、分析 SDK 或广告
- [ ] 新增用户文本同时更新了 `values/strings.xml` 和 `values-zh-rCN/strings.xml`
- [ ] 数据格式变更已提升 schema 版本并补测试
- [ ] `./gradlew :app:testDebugUnitTest` 通过
- [ ] `./gradlew :app:lintDebug` 通过
- [ ] UI 改动附了亮色/深色截图（如适用）
