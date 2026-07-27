# SDD Progress Ledger — OSS 存储 + 文件 Tools

Plan: docs/plan/oss-storage-and-file-tools-plan.md
Constraint: NO git mutations (no commit/branch/add). Work in working tree on master; user commits at the end.

- Task 1: complete (Phase 0+1, review clean: spec 11/11, Approved)
  Minor findings for final review: (1) FileUploadServiceImpl catch(IOException) misses OSSException/ClientException wrapping; (2) no compensating delete if save() fails after OSS upload (orphan object); (3) docker-compose.yml still injects DB_GENIUS_FILE_DIR + uploads volume
  Carry to Task 4 docs: README.md:282 env table, api-docs.yaml:987 storedPath field
  Carry to Task 2: parsers must close OSS InputStream with try-with-resources
- Task 2: complete (Phase 2+3, review Approved after 1 fix round)
  Fix round 1: ExcelParser/CsvParser bounded collection (200 rows, exact totalRows), MarkdownParser readNBytes, tools全链路 try-catch, Noop OCR success:false + isEnabled()
  Minor findings for final review: PdfParser message 覆盖+扫描件只看前50页; 10MB 未用 DB fileSize 读前预检; FileReadToolTest 未写(→Task 4); ocr.endpoint 未入配置文件(→Task 4); FileAccessGuard 为 public(跨包注入必要偏差); tool JSON 结构略不齐
  Key contracts: ToolContext keys "userId"(Long) / "allowedFileIds"(Set<Long>); OcrService.recognize(byte[])/isEnabled(); OCR SDK com.aliyun:ocr_api20210707:3.1.3
- Task 3: complete (Phase 4 wiring, review Approved, no fix round)
  Full build green. PdfParser compile fix (Loader.loadPDF→readAllBytes) included.
  Minor: Set.copyOf NPE unreachable; duplicate fileIds listed twice (harmless)
  Verify in Task 4: re-run full build; smoke-observe empty toolContext rejection; i18n of error messages (Task 1 English texts)
- Task 4: pending (Phase 5 tests/docs/build)
- Task 4: complete (Phase 5 tests/docs, 85 tests green) + fix round 1 (OCR endpoint ocr.→ocr-api., 6 files 8 spots, tests re-green)
- Final review: complete — Ready after blocker fix (fixed). Security checklist 7/7. Cross-task consistency pass.
  Deferred to future iterations: OSSException wrapping; orphan-object compensating delete; WorkflowHandler toolContext wiring unit test
  NOT done: real-环境手动冒烟 (needs real Aliyun OSS/OCR resources) — user to run per plan §6.3
ALL TASKS COMPLETE. No git commits made; all changes in working tree on master awaiting user review/commit.
