# MNLeaderboards  (Paper 1.21.11)

Animated, rotating leaderboards. Commands: **/mnlb** and **/lb**  (permission `mnlb.admin`).

## Build
1. JDK 21 + Maven install karo
2. Is folder mein `mvn package` chalao (ya `build.bat` / `build.sh`)
3. `target/MNLeaderboards-1.1.0.jar` ko server ke `plugins/` mein daal do

(Maven na ho to folder ko GitHub repo mein upload karo - `.github/workflows/build.yml` khud jar bana dega, Actions tab se download.)

## Use
| Command | Kaam |
|---|---|
| `/mnlb create <id> [board,board]` | apne saamne display lagao |
| `/mnlb preview [board\|all] [demo]` | **private preview**, sirf tum dekhoge (auto-end) |
| `/mnlb preview stop` | preview band |
| `/mnlb flip <id>` | text ulta dikhe to 180° ghumao |
| `/mnlb move / delete / list / boards / tp <id>` | manage |
| `/mnlb setboards <id> <boards...>` | kaunse boards rotate hon |
| `/mnlb skip <id>` | agle board par jao |
| `/mnlb set\|add <board> <player> <n>` | custom board ki values |
| `/mnlb reload`, `/mnlb refresh` | config reload / scores dobara read |

## Look (preview jaisa)
- Koi black backdrop nahi: bada bold title (board ke gradient colour mein) + gradient underline
- Rows: ① ② ③ circled rank, player head, naam, dotted filler, value, aur board ka icon
- Sabse neeche aapki apni line: `#75 Name ..... value` (sirf aapko dikhti hai)
- Har board alag display: `/mnlb create kills kills`, `/mnlb create deaths deaths` ... phir `/mnlb move` se paas paas rakho
- `config.yml` mein `title-scale`, `circled-ranks`, `value-icons`, `panel.enabled` se badal sakte ho

## Animation (har board ke liye)
1. **Title typing** - letter by letter, sound ke saath, gradient colour
2. **Rows slide-in** - ek ek karke, springy overshoot + fade, sound ki pitch upar jaati hai
3. **Hold** - #1 ka naam gold shimmer, underline progress ki tarah bharti hai
4. **Exit** - rows right side slide-out, title erase, phir agla board
