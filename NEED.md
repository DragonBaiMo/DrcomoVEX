# DrcomoCoreLib 缺失功能记录

- 数值字符串校验与加减运算
  - 位置: `src/main/java/cn/drcomo/utils/MathUtils.java`
  - 用途: 判断字符串是否为数字并执行加减计算，在变量修改时进行输入校验与数值更新。
  - 建议: 在 `cn.drcomo.corelib.math` 模块中提供 `NumberUtil.isNumeric(String)` 与 `NumberUtil.add(double, double)` 等静态方法。

