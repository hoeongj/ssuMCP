# Library Seat Agent Handoff

Created from the user's seat-map screenshots on 2026-06-06.

## Current Implementation

- Added static room catalog resource: `src/main/resources/library/seat-room-catalog.json`.
- Added static seat metadata scaffold: `src/main/resources/library/seat-catalog.json`.
- Added REST endpoint: `GET /api/library/seat-catalog?floorCode=&roomCode=&includeLayout=`.
- Added read-only MCP tool `get_library_seat_catalog(floor_code?, room_code?, include_layout?)`.
- Added private read-only MCP tool `recommend_library_seats(...)`.
- Recommendation combines live availability from `LibrarySeatService` with static seat metadata, then returns ranked seats.
- Reservation still uses the existing two-step flow: `prepare_reserve_library_seat` then `confirm_action`.

## Screenshot Mapping

| Image | Room | Current floor mapping | Notes |
| --- | --- | --- | --- |
| #1 | 숭실스퀘어ON | 2F | Seats 1-110 plus booths B1-B2. Types: general, booth, sofa, monitor, separated. |
| #2 | 오픈열람실(2F) | 2F | Seats 1-232. Main normal reservation candidate. |
| #3 | PC존/멀티존 | 5F | Numbered seats 1-98. P1-P8 and M1-M24 are free-use seats and should not be reserved. |
| #4 | 리클라이너 | 5F | R1-R6. Include time-limit warning before final reservation. |
| #5 | 마루열람실 | 6F | Seats appear to run 1-245. Existing mock 6F likely includes this. |
| #6 | 대학원열람실 | 6F | Seats 1-62. Non-graduate reservation alert: `해당유형은 사용이 불가능한 신분입니다.` |
| #7 | 지하열람실(B1) | Static only | B1 is not in `LibraryFloor` or current real connector yet. Confirm upstream floor value first. |

## Known Limits

- `RealLibrarySeatConnector` currently calls `/pyxis-api/1/seat-rooms?smufMethodCode=PC&branchGroupId=1` and parses room counts only.
- The real connector does not yet populate `LibrarySeatZone.seats`, so recommendation will honestly return no seat-level picks in real mode until the seat-map/list endpoint is captured.
- `LibraryFloor` currently supports only `2`, `5`, `6`. B1 is present in the static room catalog only.
- `ssuAI` runtime code currently uses `LibraryFloorCode` for the existing floor tabs and does not expose B1 yet.
- `prepare_reserve_library_seat` accepts `floor` and `seat_id` only. It does not yet carry `roomCode` or graduate-only policy.
- `seat-catalog.json` intentionally contains representative sample seats, not the full hardcoded seat set yet.

## Next Claude Task

1. Capture Pyxis network traffic while opening each room's seat map and the seat list tab:
   - request URL
   - method
   - request body/query params
   - response JSON shape
   - room id / floor id / seat id fields
   - remove tokens/cookies before committing fixtures
2. Capture one normal reservation attempt and one graduate-room denial:
   - normal prepare/reserve request and response
   - graduate denial response or browser alert source
3. Extend backend DTOs:
   - add room id/code to live seat status
   - populate `LibrarySeatZone.seats`
   - map external seat numbers to `seat-catalog.json`
4. Add B1 only after confirming Pyxis floor value:
   - either extend `LibraryFloor` with B1, or replace floor-only model with room-based model.
5. Expand `seat-catalog.json` from screenshots/API into full per-seat metadata:
   - `roomCode`, `externalSeatId`, `seatType`, `window`, `outlet`, `standing`, `edge`, `quiet`, `nearEntrance`, `audience`.
6. Frontend follow-up in `ssuAI`:
   - preference UI for window/outlet/standing/edge/quiet/entrance
   - show recommendation cards
   - route selected recommendation into `prepare_reserve_library_seat`
   - show graduate-only/time-limit warnings before confirm.

## Capture Checklist For User

- Browser devtools Network tab filtered by `pyxis-api`.
- Open every room in the screenshot set and switch between `좌석배치도` and `좌석목록`.
- Click one available normal seat, stop before irreversible confirmation if possible, and save request/response.
- Try one 대학원열람실 seat with a non-graduate account and save the alert/request/response.
- Try B1 and save whether the API uses `floor=-1`, `B1`, another floor id, or a room id only.
