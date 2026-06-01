# System Usability Scale (SUS) — questionnaire

Standard Brooke 1986 10-item form. The participant rates each statement on a 5-point Likert scale from **Stimme überhaupt nicht zu (1)** to **Stimme voll zu (5)**.

Score the SUS as follows (Brooke, 1996 update):

- **Odd-numbered items** (positive phrasing): subtract 1 from the rating.
- **Even-numbered items** (negative phrasing): subtract the rating from 5.
- Sum the 10 adjusted scores; multiply by 2.5 ⇒ a value between 0 and 100.

The panel median is the **SUS median** in [DR-019](../../decision-record.md#dr-019--phase-e-usability-acceptance-bar)'s acceptance bar (≥ 70 to pass).

---

## Deutsch

For each statement, mark one box per row.

| # | Aussage | 1 — überhaupt nicht | 2 | 3 | 4 | 5 — voll und ganz |
|---|---|---|---|---|---|---|
| 1 | Ich glaube, dass ich das System gern häufig benutzen würde. | □ | □ | □ | □ | □ |
| 2 | Ich fand das System unnötig komplex. | □ | □ | □ | □ | □ |
| 3 | Ich fand das System einfach zu benutzen. | □ | □ | □ | □ | □ |
| 4 | Ich glaube, ich würde die Hilfe einer technisch versierten Person benötigen, um das System benutzen zu können. | □ | □ | □ | □ | □ |
| 5 | Ich fand, die verschiedenen Funktionen in diesem System waren gut integriert. | □ | □ | □ | □ | □ |
| 6 | Ich denke, das System enthielt zu viele Inkonsistenzen. | □ | □ | □ | □ | □ |
| 7 | Ich kann mir vorstellen, dass die meisten Menschen den Umgang mit diesem System sehr schnell lernen. | □ | □ | □ | □ | □ |
| 8 | Ich fand das System sehr umständlich zu benutzen. | □ | □ | □ | □ | □ |
| 9 | Ich fühlte mich bei der Benutzung des Systems sehr sicher. | □ | □ | □ | □ | □ |
| 10 | Ich musste eine Menge lernen, bevor ich anfangen konnte, mit dem System zu arbeiten. | □ | □ | □ | □ | □ |

---

## English

| # | Statement | 1 — Strongly disagree | 2 | 3 | 4 | 5 — Strongly agree |
|---|---|---|---|---|---|---|
| 1 | I think that I would like to use this system frequently. | □ | □ | □ | □ | □ |
| 2 | I found the system unnecessarily complex. | □ | □ | □ | □ | □ |
| 3 | I thought the system was easy to use. | □ | □ | □ | □ | □ |
| 4 | I think that I would need the support of a technical person to be able to use this system. | □ | □ | □ | □ | □ |
| 5 | I found the various functions in this system were well integrated. | □ | □ | □ | □ | □ |
| 6 | I thought there was too much inconsistency in this system. | □ | □ | □ | □ | □ |
| 7 | I would imagine that most people would learn to use this system very quickly. | □ | □ | □ | □ | □ |
| 8 | I found the system very cumbersome to use. | □ | □ | □ | □ | □ |
| 9 | I felt very confident using the system. | □ | □ | □ | □ | □ |
| 10 | I needed to learn a lot of things before I could get going with this system. | □ | □ | □ | □ | □ |

---

## Scoring sheet (analyst-only, do not show to participant)

For each participant `p`, given ratings `r1..r10`:

```
adjusted[i] = (i odd ? r[i] - 1 : 5 - r[i])
SUS(p)      = (sum of adjusted) × 2.5
```

Panel median = median(SUS) across participants per role.

| SUS range | Interpretation (Brooke) |
|---|---|
| ≥ 80 | Excellent |
| 70–79 | Good |
| 60–69 | OK |
| 50–59 | Poor |
| < 50 | Unacceptable |

DR-019 acceptance bar: **panel median ≥ 70**.
