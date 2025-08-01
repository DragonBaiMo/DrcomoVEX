### `HttpUtil.java`

**1. 概述 (Overview)**

  * **完整路径:** `cn.drcomo.corelib.net.HttpUtil`
  * **核心职责:** 通过 Java 11 `HttpClient` 提供异步的 GET、POST 与文件上传功能。
    构建器允许配置代理、基础 URI、默认请求头、超时时间以及失败重试次数，并将网络异常记录到 `DebugUtil`。

**2. 如何实例化 (Initialization)**

  * **核心思想:** `HttpUtil` 通过 `HttpUtil.newBuilder()` 创建。必须注入 `DebugUtil`
    才能输出网络日志。其余配置项可按需设定。
  * **代码示例:**
    ```java
    DebugUtil logger = new DebugUtil(myPlugin, DebugUtil.LogLevel.INFO);

    HttpUtil http = HttpUtil.newBuilder()
            .logger(logger)
            .timeout(Duration.ofSeconds(5))
            .retries(2)
            .baseUri(URI.create("https://api.example.com/"))
            .defaultHeader("User-Agent", "MyPlugin")
            .build();
    ```

**3. 常用方法 (API)**

  * #### `CompletableFuture<HttpResponse<String>> request(HttpRequest request)`

      * **功能描述:** 直接发送自定义的 HttpRequest 对象，返回完整的响应。
  * #### `CompletableFuture<String> get(String url, Map<String,String> headers)`

      * **功能描述:** 以 GET 方式请求指定 URL，返回响应文本。
  * #### `CompletableFuture<String> post(String url, String body, Map<String,String> headers)`

      * **功能描述:** 以 POST 方式发送字符串正文，并返回响应文本。
  * #### `CompletableFuture<String> upload(String url, Path path, Map<String,String> headers)`

      * **功能描述:** 将文件上传到指定地址，返回响应文本。

所有方法均在出现网络异常或超时后写入 `DebugUtil`，并在达到设置的最大重试次数后将异常
通过 `CompletableFuture` 传递给调用方。

**4. 构建器参数 (Builder Options)**

| 方法 | 说明 |
| --- | --- |
| `logger(DebugUtil)` | 日志输出工具，必填 |
| `proxy(String, int)` | 设置 HTTP 代理 |
| `timeout(Duration)` | 请求超时时间 |
| `retries(int)` | 最大重试次数 |
| `client(HttpClient)` | 使用自定义 `HttpClient` 实例 |
| `executor(Executor)` | 自定义执行器用于构建内部 `HttpClient` |
| `baseUri(URI)` | 设置基础 URI，支持相对路径 |
| `defaultHeader(String, String)` | 添加默认请求头 |

**自定义 HttpClient 示例**

```java
ExecutorService pool = Executors.newFixedThreadPool(2);
HttpUtil http = HttpUtil.newBuilder()
        .logger(logger)
        .executor(pool)
        .defaultHeader("User-Agent", "MyPlugin")
        .build();
```
