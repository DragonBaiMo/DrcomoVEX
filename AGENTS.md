# Development Guidelines (Win11 / PowerShell)

**Author:** BaiMo_  
**Principles:** First Principles, DRY, KISS, SOLID, YAGNI  
**Goals:** Clear communication, scalable architecture, high code quality, efficient and safe maintenance

---

## ## 1. Communication & Requirement Confirmation

- **Language:** All communication, comments, and logs must be in Chinese. Do not mix languages.

- **Resource Usage:** Only use interfaces, APIs, or libraries I explicitly provide or are publicly documented. Do not use reflection or dynamic method/path construction to bypass checks.

- Use `TODO` with explanation when a solution cannot be found, and do not implement guesswork or placeholder logic.


---

## 2. Design & Architecture

- **Design First:** Plan before coding complex logic or new features.
    
- **Single Responsibility & Modularity:** Separate logic, events, configs, etc. Follow SRP and SOLID principles.
    
- **Interface-Driven:** Abstract core logic via interfaces and models based on first principles. Prioritize extensibility.
    
- **Reuse Over Rebuild:** Reuse or extend existing code before adding new logic. Avoid code duplication (DRY).
    
- **KISS & YAGNI:** Keep code simple; implement only what is needed now.
    
- **Code Size:** Methods/classes 30–200 lines; split files over 500 lines, with documentation.
    
- **Directory Structure:** For large projects, use clear layered folders (e.g., `domain/`, `service/`, `controller/`).

---

## 3. Coding Standards & Quality

- **Naming:** Use clear, business-relevant names for variables, methods, and classes.
    
- **Tools:** Enforce code formatting and static analysis.
    
- **Documentation:** Public APIs/classes/interfaces require complete JavaDoc-style comments (purpose, parameters, return, exceptions, etc.). Complex logic must explain “why”, not just “what”.
    
- **Edit Merging:** Batch related changes; avoid one-line commits unless necessary.
    
- **Imports:** Import packages as needed, not in advance.
    
- **Configuration:** Never hardcode user-editable content. Config files should auto-generate only if missing. Sync sample config files with logic.

---

## 4. Asynchronous Tasks & Performance

- **Async Usage:** IO/network/cpu-heavy operations should be async, with prior risk analysis.
    
- **Thread Safety:** Main thread state updates must be safe. Use sync when unsure and document reasons.
    
- **Fallbacks:** Protect async logic with exception handling and fallback strategies.

---

## 5. Security

- **Input Validation:** Never trust external input. Enforce strict checks and whitelist strategies.
    
- **Sensitive Data:** Encrypt/store passwords & keys safely. Do not log sensitive info.
    
- **Permissions:** Enforce permission checks for all sensitive actions; follow least privilege.
    
- **Vulnerabilities:** Actively prevent SQL/command injection, path traversal, XSS, DoS, etc.

---

## 6. Data & Resource Management

- **Persistence:** Operate in memory first, then persist; use parameterized queries for DB.
    
- **Resource Management:** Release files, DB connections, sockets after use; use connection pools as needed.

---

## 7. Customization & Internationalization

- **Configurable Content:** UI texts and prompts must be in config files for localization; never hardcoded.

---

## 8. Logging & Error Handling

- **Logging:** Key actions and exceptions require logs in Chinese, but never include sensitive data.
    
- **Debug Levels:** Use graded logging for easier troubleshooting.

---

## 9. TODO Management

- **Mark TODOs:** For unfinished, temporary, or pending tasks, mark with `// TODO` and a clear reason.

---

## Final Principles

Always follow these philosophies:  
**First Principles** (abstract from fundamentals), **DRY** (no duplication), **KISS** (keep simple), **SOLID** (modular and maintainable), **YAGNI** (no premature design).  
**Clarity, planning, security, maintainability, and extensibility are the foundation of all work.**