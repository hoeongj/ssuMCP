# ADR 0035 — export_all_lms_materials 도구 추가 및 일괄 다운로드 미리보기 흐름 개선

- **Status**: Accepted
- **Date**: 2026-06-16

## 배경

현재 LMS 학습 자료를 ZIP 파일로 내보내는 흐름은 다음과 같이 4단계의 LLM 호출 단계가 필요하다:
get_my_lms_courses → get_my_lms_materials → prepare_lms_material_export → confirm_lms_material_export.
이 과정에서 LLM이 수많은 content_id 목록을 직접 수집하고 관리해야 하므로 비효율적이고 실패율이 높다.
이를 개선하기 위해 한 번의 도구 호출로 현재 학기(또는 특정 학기) 전체 과목의 자료를 자동으로 수집하여 미리보기를 제공하는 `export_all_lms_materials` 도구를 추가한다.

## 검토한 대안 및 결정 사항

### 1. `finalizeExport()` 프라이빗 헬퍼 메서드 추출
- **결정**: `LmsMaterialExportService.prepare()` 내부의 제한 필터링 및 그룹화, Pending Action 생성 로직을 `finalizeExport()` 프라이빗 메서드로 추출하여 `prepare()`와 신규 `exportAll()` 메서드가 이를 공유하도록 함.
- **이유**: 중복 코드를 방지(DRY 원칙)하고, 기존 `prepare()`의 동작 방식을 완벽히 동일하게 유지하며, 기존 테스트의 회귀성을 쉽게 보장하기 위함.

### 2. 동일한 DTO `LmsExportPrepareResponse` 반환
- **결정**: `exportAll()`이 `prepare()`와 동일한 `LmsExportPrepareResponse` DTO를 반환하도록 함.
- **이유**: 데이터 스키마를 변경하거나 새로운 DTO를 만들지 않고도 기존 흐름을 그대로 재사용할 수 있으며, 응답 메시지에 학기 레이블(예: `[2026 1학기]`)을 접두사로 붙여서 직관적인 컨텍스트를 제공하기 위함.

### 3. 기존 `confirm_lms_material_export` 도구의 재사용
- **결정**: `export_all_lms_materials`로 생성된 대기 Action 역시 기존의 `confirm_lms_material_export`를 호출하여 최종 다운로드 링크를 발급받도록 설계함.
- **이유**: 생성되는 Pending Action의 타입이 `"LMS_MATERIAL_EXPORT"`로 동일하고 학번(studentId) 기반으로 조회되므로, Action을 생성한 주체가 `prepare()`인지 `exportAll()`인지 소비 단에서 알 필요가 없으며, 기존의 비동기 ZIP 빌드 및 다운로드 처리 파이프라인을 그대로 재사용할 수 있음.

### 4. 대안 검토: 완전 자동 일괄 다운로드 (Confirm 생략하고 다운로드 URL 즉시 반환)
- **평가**: 단 한 번의 호출로 ZIP 파일의 다운로드 URL까지 즉시 반환하는 방식을 검토했으나, 사용자가 다운로드할 파일 목록, 개수, 용량 크기 및 제외 항목들을 확인하는 미리보기 단계가 누락된다. 또한 ZIP 생성 작업은 비동기 빌드 리소스를 많이 소모하므로, 사용자의 확인 절차 없이 즉시 생성하는 것은 리소스 낭비 위험이 커서 반려되었다.
- **결정**: 미리보기 정보를 확인한 후 최종 승인 시점에 빌드를 시작하는 preview-then-confirm 흐름을 유지한다.

## 결과

- **LLM 호출 단계 단축**: 자료 다운로드 준비가 4단계에서 2단계(`export_all_lms_materials` → `confirm_lms_material_export`)로 단순화되어 토큰 사용량과 실패 가능성이 대폭 감소한다.
- **구현 일관성 유지**: 비동기 빌드 큐 처리 및 다운로드 권한 모델의 수정 없이, 프론트엔드/LLM 클라이언트와의 스키마 정합성을 완벽하게 유지한다.
