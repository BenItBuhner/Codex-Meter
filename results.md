| # | Step | Expected | Result | Seen after (ms, incl. ~40 ms adb poll) |
|---|---|---|---|---|
| 1 | Cold open Claude | shown | PASS | 4761 |
| 2 | Home | hidden | PASS | 3001 |
| 3 | Reopen Claude from Recents (warm) | shown | PASS | 2715 |
| 4 | Home | hidden | PASS | 103 |
| 5 | Reopen Claude from its icon (warm) | shown | PASS | 1835 |
| 6 | Switch straight to ChatGPT | shown | PASS | 537 |
| 7 | Monitor swaps to Codex for ChatGPT | Codex usage | PASS | 2226 |
| 8 | Pull down shade over ChatGPT (stays) | shown | PASS | 465 |
| 9 | Home | hidden | PASS | 1898 |
| 10 | Loop 1: Claude from Recents | shown | PASS | 1333 |
| 11 | Loop 1: Home | hidden | PASS | 2649 |
| 12 | Loop 1: Claude from icon | shown | PASS | 1423 |
| 13 | Loop 1: Back out to home | hidden | PASS | 2352 |
| 14 | Loop 2: Claude from Recents | shown | PASS | 2083 |
| 15 | Loop 2: Home | hidden | PASS | 1963 |
| 16 | Loop 2: Claude from icon | shown | PASS | 2826 |
| 17 | Loop 2: Back out to home | hidden | PASS | 1775 |
| 18 | Loop 3: Claude from Recents | shown | PASS | 2754 |
| 19 | Loop 3: Home | hidden | PASS | 1889 |
| 20 | Loop 3: Claude from icon | shown | PASS | 1731 |
| 21 | Loop 3: Back out to home | hidden | PASS | 2354 |
| 22 | Loop 4: Claude from Recents | shown | PASS | 2335 |
| 23 | Loop 4: Home | hidden | PASS | 1675 |
| 24 | Loop 4: Claude from icon | shown | PASS | 1541 |
| 25 | Loop 4: Back out to home | hidden | PASS | 2793 |
| 26 | Loop 5: Claude from Recents | shown | PASS | 2341 |
| 27 | Loop 5: Home | hidden | PASS | 1724 |
| 28 | Loop 5: Claude from icon | shown | PASS | 1600 |
| 29 | Loop 5: Back out to home | hidden | PASS | 1386 |
| 30 | Loop 6: Claude from Recents | shown | PASS | 2108 |
| 31 | Loop 6: Home | hidden | PASS | 1359 |
| 32 | Loop 6: Claude from icon | shown | PASS | 645 |
| 33 | Loop 6: Back out to home | hidden | PASS | 1768 |
| 34 | Press Stop while in Claude | hidden | PASS | 142 |
| 35 | Still in Claude 2 s later (stays dismissed) | hidden | PASS | |
| 36 | Home | hidden | PASS | 355 |
| 37 | Reopen Claude (shows again) | shown | PASS | 833 |
| 38 | Quiet 1: Claude from icon | shown | PASS | 926 |
| 39 | Quiet 1: Home | hidden | PASS | 1042 |
| 40 | Quiet 1: Claude from Recents | shown | PASS | 706 |
| 41 | Quiet 1: Home | hidden | PASS | 1154 |
| 42 | Quiet 1: ChatGPT from icon | shown | PASS | 939 |
| 43 | Quiet 1: Back out to home | hidden | PASS | 1269 |
| 44 | Quiet 2: Claude from icon | shown | PASS | 907 |
| 45 | Quiet 2: Home | hidden | PASS | 1172 |
| 46 | Quiet 2: Claude from Recents | shown | PASS | 771 |
| 47 | Quiet 2: Home | hidden | PASS | 1249 |
| 48 | Quiet 2: ChatGPT from icon | shown | PASS | 1294 |
| 49 | Quiet 2: Back out to home | hidden | PASS | 1418 |
| 50 | Quiet 3: Claude from icon | shown | PASS | 805 |
| 51 | Quiet 3: Home | hidden | PASS | 1121 |
| 52 | Quiet 3: Claude from Recents | shown | PASS | 521 |
| 53 | Quiet 3: Home | hidden | PASS | 1140 |
| 54 | Quiet 3: ChatGPT from icon | shown | PASS | 1397 |
| 55 | Quiet 3: Back out to home | hidden | PASS | 1102 |
| 56 | Quiet 4: Claude from icon | shown | PASS | 1053 |
| 57 | Quiet 4: Home | hidden | PASS | 1067 |
| 58 | Quiet 4: Claude from Recents | shown | PASS | 666 |
| 59 | Quiet 4: Home | hidden | PASS | 1088 |
| 60 | Quiet 4: ChatGPT from icon | shown | PASS | 1198 |
| 61 | Quiet 4: Back out to home | hidden | PASS | 1438 |
| 62 | Quiet 5: Claude from icon | shown | PASS | 900 |
| 63 | Quiet 5: Home | hidden | PASS | 1098 |
| 64 | Quiet 5: Claude from Recents | shown | PASS | 631 |
| 65 | Quiet 5: Home | hidden | PASS | 1163 |
| 66 | Quiet 5: ChatGPT from icon | shown | PASS | 1079 |
| 67 | Quiet 5: Back out to home | hidden | PASS | 1364 |
| 68 | Quiet 6: Claude from icon | shown | PASS | 1055 |
| 69 | Quiet 6: Home | hidden | PASS | 1027 |
| 70 | Quiet 6: Claude from Recents | shown | PASS | 756 |
| 71 | Quiet 6: Home | hidden | PASS | 1366 |
| 72 | Quiet 6: ChatGPT from icon | shown | PASS | 1019 |
| 73 | Quiet 6: Back out to home | hidden | PASS | 1430 |

### On-device: step start to show/hide (includes the input tool and, for hides, the 0.6 s grace)

| # | Step | ms |
|---|---|---|
| 1 | Cold open Claude | 4348 ms |
| 2 | Home | 2316 ms |
| 3 | Reopen Claude from Recents (warm) | 4039 ms |
| 4 | Home | no change logged |
| 5 | Reopen Claude from its icon (warm) | 2126 ms |
| 6 | Switch straight to ChatGPT | 2770 ms |
| 8 | Pull down shade over ChatGPT (stays) | no change logged |
| 9 | Home | 1328 ms |
| 10 | Loop 1: Claude from Recents | 2741 ms |
| 11 | Loop 1: Home | 1720 ms |
| 12 | Loop 1: Claude from icon | no change logged |
| 13 | Loop 1: Back out to home | 2310 ms |
| 14 | Loop 2: Claude from Recents | 3187 ms |
| 15 | Loop 2: Home | 1458 ms |
| 16 | Loop 2: Claude from icon | 2696 ms |
| 17 | Loop 2: Back out to home | 1650 ms |
| 18 | Loop 3: Claude from Recents | 4218 ms |
| 19 | Loop 3: Home | 1681 ms |
| 20 | Loop 3: Claude from icon | 1716 ms |
| 21 | Loop 3: Back out to home | 2007 ms |
| 22 | Loop 4: Claude from Recents | 3600 ms |
| 23 | Loop 4: Home | 1469 ms |
| 24 | Loop 4: Claude from icon | 1462 ms |
| 25 | Loop 4: Back out to home | 2421 ms |
| 26 | Loop 5: Claude from Recents | 3110 ms |
| 27 | Loop 5: Home | 1236 ms |
| 28 | Loop 5: Claude from icon | 1693 ms |
| 29 | Loop 5: Back out to home | 1034 ms |
| 30 | Loop 6: Claude from Recents | 3183 ms |
| 31 | Loop 6: Home | 1340 ms |
| 32 | Loop 6: Claude from icon | 746 ms |
| 33 | Loop 6: Back out to home | 1448 ms |
| 34 | Press Stop while in Claude | no change logged |
| 36 | Home | 1124 ms |
| 37 | Reopen Claude (shows again) | 789 ms |
| 38 | Quiet 1: Claude from icon | 578 ms |
| 39 | Quiet 1: Home | 1017 ms |
| 40 | Quiet 1: Claude from Recents | 1863 ms |
| 41 | Quiet 1: Home | 1165 ms |
| 42 | Quiet 1: ChatGPT from icon | 1096 ms |
| 43 | Quiet 1: Back out to home | 1354 ms |
| 44 | Quiet 2: Claude from icon | 921 ms |
| 45 | Quiet 2: Home | 1200 ms |
| 46 | Quiet 2: Claude from Recents | 1856 ms |
| 47 | Quiet 2: Home | 1105 ms |
| 48 | Quiet 2: ChatGPT from icon | 1461 ms |
| 49 | Quiet 2: Back out to home | 1318 ms |
| 50 | Quiet 3: Claude from icon | 904 ms |
| 51 | Quiet 3: Home | 1091 ms |
| 52 | Quiet 3: Claude from Recents | 1561 ms |
| 53 | Quiet 3: Home | 1228 ms |
| 54 | Quiet 3: ChatGPT from icon | 1262 ms |
| 55 | Quiet 3: Back out to home | 1805 ms |
| 56 | Quiet 4: Claude from icon | 1256 ms |
| 57 | Quiet 4: Home | 1072 ms |
| 58 | Quiet 4: Claude from Recents | 1801 ms |
| 59 | Quiet 4: Home | 1019 ms |
| 60 | Quiet 4: ChatGPT from icon | 1199 ms |
| 61 | Quiet 4: Back out to home | 1735 ms |
| 62 | Quiet 5: Claude from icon | 700 ms |
| 63 | Quiet 5: Home | 1056 ms |
| 64 | Quiet 5: Claude from Recents | 1788 ms |
| 65 | Quiet 5: Home | 1247 ms |
| 66 | Quiet 5: ChatGPT from icon | 1086 ms |
| 67 | Quiet 5: Back out to home | 1659 ms |
| 68 | Quiet 6: Claude from icon | 990 ms |
| 69 | Quiet 6: Home | 1061 ms |
| 70 | Quiet 6: Claude from Recents | 1825 ms |
| 71 | Quiet 6: Home | 1126 ms |
| 72 | Quiet 6: ChatGPT from icon | 1130 ms |
| 73 | Quiet 6: Back out to home | 1392 ms |

Opens, recorder off: median 1227 ms, max 1863 ms over 18 steps

Leaves, recorder off: median 1182 ms, max 1805 ms over 18 steps
