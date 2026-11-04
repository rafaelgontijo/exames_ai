# Figtree (bundled fonts)

- Origin: https://github.com/erikdkennedy/figtree, commit `032dfa7fe219ef3a02890d6d3add84eacc9aebfe`, path `fonts/ttf/`.
- License: SIL Open Font License 1.1 (`OFL.txt`, SHA-256 `140d37233e7f3ce7313798befa9600893bcceaf41a55fa0fa5ad52f7f657a268`).
- Static TTFs (not variable, not WOFF2) so they load on API 24. Downloaded once at integration time and committed;
  the app never fetches fonts at runtime and uses no Google Fonts/font provider.

| Weight | Resource | SHA-256 |
| --- | --- | --- |
| 400 | `figtree_regular.ttf` | `448d74e778cc9774db27bd86cf451bba982b2e4dc348f610390eb6e3439cf6ca` |
| 500 | `figtree_medium.ttf` | `358a4de79ae6c3fd7c6a54fcd778c1101ba376af75b8463e715df6988387bc19` |
| 600 | `figtree_semibold.ttf` | `35fc4508d627f8402a7d5ae91d842d1adb36846eea902c0a849642afecd0ac74` |
| 700 | `figtree_bold.ttf` | `71a35e2bd92a05427e2dc9897bab41f8a865800e148238007735b5934508198a` |
| 800 | `figtree_extrabold.ttf` | `c86cc3d813f6af7723474ff78ba1d1351254d1f4d4bfeedf58d9e8ed389b1dc4` |

`FigtreeFontsTest` verifies these hashes. Changing a font requires updating this table deliberately.
