# 语言和工具版本约定
- 主语言：Java: Java 21
    - JDK 路径：E:\tools\jdk-21.0.13+9
- 项目框架：Springboot: Springboot 4.1.x
    - 直接依赖约定：
        - 优先使用 starter 依赖
        - 使用 Lombok
        - 使用 Jackson 3
        - 使用 JUnit 6
        - 使用 Logback 日志系统
- 构建工具：Gradle: Gradle 8.14.x
    - 路径：E:\tools\gradle-8.14.5-all
    - 构建：多项目构建 Gradle 项目
    - 暂时不使用 wrapper


# 代码风格
参考建议的规范或最佳实践：
- 代码注释一律使用英文
- 尽可能使用有意义的变量名

# 性能
设计实现/实现时应适当考虑交互性能、处理性能等综合性能

# 安全
必须适当考虑程序安全，避免异常崩溃、被攻击等