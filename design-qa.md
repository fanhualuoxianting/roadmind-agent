# RoadMind Agent 车辆实时区域 Design QA

## Source visual truth

- Primary target: `D:\Temp\codex-clipboard-d05db6bd-d170-4a8c-867a-5fe917453ba3.png` (1487 × 1058 px). It is the latest full-page reference for the straight rear-view road vehicle and the expanded `当前车辆状态` card.
- Latest correction target: `D:\Temp\codex-clipboard-0c6de5e1-63a5-41cc-9ef0-67bf64f5fb10.png` (622 × 341 px). It identifies the status-card vehicle's horizontal position and the route line's missing connection to B.
- Latest route correction target: `D:\Temp\codex-clipboard-ebd28648-56f5-470f-89ea-517df12bb33b.png` (578 × 123 px). It shows the B marker sitting above the route endpoint at the wider route-card scale.
- Latest workbench visual target: `D:\Temp\codex-clipboard-b6e23ea9-212c-458b-9a86-0cbee6b740ff.png` and `D:\Temp\codex-clipboard-42ab478a-9ec9-491c-ac6f-a4971caa72fb.png`. They define the larger text hierarchy and distinct user/Agent avatars.
- Status-card detail: `D:\Temp\codex-clipboard-2b94545e-1a0f-46b5-80c5-7088fd3bf939.png` (615 × 105 px).
- Regression reference: `D:\Temp\codex-clipboard-ba54e7e8-dc1f-4ac0-b9a6-b07c924572f2.png`, which shows the diagonal road vehicle that triggered the correction.
- Vehicle art-direction references: `D:\Temp\codex-clipboard-c7ce9d33-1b8b-4378-be17-812a26ecee87.png` and `D:\Temp\codex-clipboard-fcf16399-646b-4478-bd9a-eb4612c04740.png`.

## Implementation evidence

- Local preview: `http://127.0.0.1:5178/agent`.
- Browser-rendered implementation: `E:\codex-projects\roadmind-agent\qa-artifacts\agent-viewport.png` (1265 × 712 px PNG; CSS viewport reported as 1280 × 720 px; device pixel ratio 1.25).
- Latest browser-rendered implementation: `E:\codex-projects\roadmind-agent\qa-artifacts\agent-viewport-route-fixed.png` (1265 × 712 px PNG; same CSS viewport and device pixel ratio).
- Latest workbench implementation: `E:\codex-projects\roadmind-agent\qa-artifacts\agent-workbench-avatar-typography.png`, captured after the Shanghai Hongqiao route update with the new avatar assets and larger typography.
- Focused road evidence: `E:\codex-projects\roadmind-agent\qa-artifacts\left-panel-comparison.png`.
- Focused status-card evidence: `E:\codex-projects\roadmind-agent\qa-artifacts\vehicle-status-comparison.png`.
- Latest status-card positioning evidence: `E:\codex-projects\roadmind-agent\qa-artifacts\vehicle-status-positioned.png`.
- Latest route evidence: `E:\codex-projects\roadmind-agent\qa-artifacts\route-comparison.png`.
- Latest dynamic-route evidence: `E:\codex-projects\roadmind-agent\qa-artifacts\agent-viewport-route-fixed.png` plus live DOM endpoint measurement below.
- Full-view comparison: `E:\codex-projects\roadmind-agent\qa-artifacts\ui-comparison.png`; the source top viewport was normalized to 1265 × 712 px and placed beside the current browser capture.
- The reference is a taller 1487 × 1058 px composition while the local browser capture is the visible 1280 × 720 CSS viewport, so the full-view comparison is limited to the shared above-the-fold region. Focused comparisons use equal normalized output sizes.

## State and interaction checks

- Route: `/agent`.
- State: idle Agent view with the existing Chinese request, `LIVE MODEL` pill, default vehicle data, and no active task/error state.
- Existing request composer, send action, quick suggestions, route preview, center workbench, header, navigation, and cabin dock remain present.
- DOM geometry: `.drive-preview` 225.1 × 748.6 px; `.agent-workbench` 517.1 × 748.6 px; `.task-insight` 289 × 748.6 px; `.vehicle-card` 257.4 × 134.3 px; `.cabin-dock` 1057.2 × 67 px.
- Browser console warnings/errors: none observed.
- Latest browser verification: `/agent` rendered with `LIVE MODEL`, no server error text, and empty warning/error logs.
- Latest route verification: the line is calculated from the actual A/B marker centers. At the checked viewport the endpoint error was `0.0053 CSS px`.
- Latest workbench verification: the user and Agent labels render with distinct raster avatars, the h1 is 38 px at the captured breakpoint, conversation text is 14 px, and the route remains `南京软件谷 → 上海虹桥站`.

## Full-view comparison evidence

- The left road remains a bright three-lane scene with the same speed readout, limit circles, social vehicles, HUD cues, lower metrics, borders, and rounded frame.
- The center Agent workbench, top bar, navigation, and bottom cabin dock remain visually unchanged. The only intentional right-column change is the latest requested vehicle-status card expansion.
- The center Agent workbench now intentionally follows the supplied initial UI direction more closely: larger heading/body text, clearer message spacing, and separate user and assistant avatar imagery replace the old `人`/`RM` glyphs.
- The primary road vehicle now uses a straight rear/top raster view whose longitudinal axis is vertical and centered on the road direction arrow; it no longer cuts diagonally across the lane.

## Focused region comparison evidence

- `left-panel-comparison.png`: source on the left, implementation on the right. The road perspective, centered rear vehicle, glacier-blue body, dark roof, coral tail lamp, and restrained contact shadow are readable at equal normalized size.
- `vehicle-status-comparison.png`: source on the left, implementation on the right. The implementation now contains the status title, lock state, climate state, location label, full `南京市雨花台区软件大道` text, and a front-left three-quarter glacier-blue vehicle instead of the old `RM` capsule.
- `route-comparison.png`: source on the left, implementation on the right. The blue route now starts at A's center and terminates beneath B's center, with the orange B marker covering the endpoint rather than floating beyond it.
- The latest route correction removes the fixed `-8deg` geometry. A `ResizeObserver` and window resize listener recompute the line's start, end, length, and angle, so a wider or narrower route card keeps B on the line instead of drifting above it.
- `agent-workbench-avatar-typography.png`: the workbench title, description, conversation labels, user message, Agent response, and intent cards are visibly larger and retain the original light blue cockpit hierarchy. The user avatar is a soft blue profile badge; the Agent avatar is a pale silver/sky-blue robot badge.

## Findings

- No actionable P0/P1/P2 issues remain.
- Fonts and typography: existing cockpit typography and Chinese copy remain unchanged; the new status details use the same muted blue hierarchy and fit the compact card at the verified viewport.
- Spacing and layout rhythm: the road card, three-lane composition, metric cards, right-column card frame, center workbench, header, navigation, and cabin dock retain their existing positions. The status image is contained within the card and does not cover the center column.
- Colors and visual tokens: the light gray-blue road, morning haze, sky-blue/mint HUD accents, glacier-blue vehicles, and restrained coral tail lamp remain consistent with the RoadMind cockpit language; no dark background was introduced.
- Image quality and asset fidelity: `roadmind-web/src/assets/roadmind-digital-twin-rear.png` is a generated transparent rear-view vehicle cutout; `roadmind-web/src/assets/roadmind-digital-twin-status-front-v2.png` is a generated transparent front-left status-card vehicle cutout. Both are raster assets, unbranded, and rendered without CSS car drawings or placeholder blocks.
- Copy and content: `68 km/h`, `68%`, `412 km`, `舒适`, the existing workbench text, and all surrounding Chinese copy are preserved. The new status card adds only the requested `已锁车`, `空调未开启`, and `位置` details.
- P3 follow-up only: the compact 1280 × 720 browser viewport makes the status card and address text smaller than the 1487 × 1058 reference; the address now fits without ellipsis at the verified viewport, and this does not change the requested layout.

## Comparison history

### Pass 1

- Finding: the initial rendered car was too small for the requested primary visual focus.
- Fix: enlarged the road vehicle while preserving the road frame and summary metrics.
- Evidence: `qa-artifacts/left-panel-comparison.png`.

### Pass 2

- Finding: the first handoff still showed a visibly diagonal car; the user explicitly asked “这怎么是斜的”.
- Fix: the root cause was the source asset's own yaw combined with CSS rotation. Replaced it with a straight rear/top vehicle asset and removed the rotation completely; `.road-car-main` now uses only `transform: translateX(-50%)`.
- Post-fix evidence: `qa-artifacts/left-panel-comparison.png` and `qa-artifacts/agent-viewport.png`.

### Pass 3

- Finding: the right `当前车辆状态` area was still the old `RM` capsule and did not match the supplied reference.
- Fix: expanded the card with lock, climate, location, and address rows plus a real vehicle raster asset, without changing the center workbench or surrounding layout.
- Post-fix evidence: `qa-artifacts/vehicle-status-comparison.png`.

### Pass 4

- Finding: the first status-card vehicle faced the wrong direction relative to the supplied reference.
- Fix: replaced it with a front-left three-quarter glacier-blue asset so the car nose faces left; reduced only the address text size to 7.5 px so the full location remains visible in the compact card.
- Post-fix evidence: refreshed `qa-artifacts/vehicle-status-comparison.png`, `qa-artifacts/agent-viewport.png`, and empty browser warning/error logs.

### Pass 5

- Finding: the status-card vehicle sat too close to the lower and right edges, making its relationship to the `位置` row feel visually off.
- Fix: kept the card structure unchanged; moved only the vehicle image upward with `bottom: 24px`, reduced it to `56%` width, and restored a small right inset with `right: -2px`.
- Post-fix evidence: `qa-artifacts/vehicle-status-positioned.png` and the refreshed `qa-artifacts/vehicle-status-comparison.png` show a centered visual baseline, clear location text, and no overlap with the status rows.

### Pass 6

- Finding: the status-card vehicle still needed to move farther left, and the route line ended short of the orange B marker.
- Fix: moved the vehicle to `right: 14px`; anchored `.route-line` at A's center with `left: calc(12% + 11.5px)`, `top: calc(52% + 8.5px)`, `width: calc(76% - 23px)`, and the existing `-8deg` slope. At the representative 257.4 × 96 px map, the calculated line endpoint and B center are both `(215.0, 37.4)`.
- Post-fix evidence: `qa-artifacts/vehicle-status-comparison.png`, `qa-artifacts/route-comparison.png`, and the final browser capture with empty warning/error logs.

### Pass 7

- Finding: at the wider route-card scale, the previous fixed `-8deg` line still placed the endpoint above the orange B marker.
- Fix: replaced the percentage/angle shortcut with a DOM-measured line. `AgentView.vue` reads the actual center points of `.route-start` and `.route-end`, computes the exact distance and angle, and refreshes the inline geometry through `ResizeObserver` and window resize events. The line now uses the same coordinate system as the markers.
- Post-fix evidence: `qa-artifacts/agent-viewport-route-fixed.png`, live browser endpoint error `0.0053 CSS px`, and empty warning/error logs.

### Pass 8

- Finding: the center workbench still used text glyphs (`人` and `RM`) for avatars and its small typography drifted away from the supplied initial UI direction.
- Fix: generated two independent raster avatar assets, removed the chroma-key background, wired them into `AgentView.vue`, enlarged the workbench heading, conversation text, labels, intent cards, and composer text, and preserved the existing layout and Chinese copy.
- Post-fix evidence: `qa-artifacts/agent-workbench-avatar-typography.png`, route state `南京软件谷 → 上海虹桥站`, and empty browser warning/error logs.

## Implementation checklist

- [x] Replace the diagonal road vehicle with a centered straight rear/top digital-twin vehicle.
- [x] Keep `68 km/h`, `68%`, `412 km`, `舒适`, road lanes, HUD, borders, and metric-card positions.
- [x] Expand the right vehicle-status card with the requested vehicle state and location details.
- [x] Use real raster vehicle assets with transparent backgrounds; no CSS car placeholder or brand mark.
- [x] Keep the middle workbench, top bar, navigation, and bottom cabin dock unchanged.
- [x] Capture full-view and focused source/implementation comparisons.
- [x] Run `npm run test -- --run`: 7 test files, 18 tests passed.
- [x] Run `npm run build`: type-check and Vite production build passed.
- [x] Check browser warnings/errors: none observed.
- [x] Recheck status-card vehicle position after user feedback.
- [x] Reconnect the route line to the B marker after user feedback.
- [x] Recalculate the route line from the actual A/B marker centers at responsive sizes.
- [x] Replace `人` and `RM` text glyphs with dedicated user and Agent avatar assets.
- [x] Increase workbench typography while preserving the existing page structure and copy.

final result: passed
