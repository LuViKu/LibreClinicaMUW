# LibreClinica MUW — Klinische Studiendaten-Plattform der Augenklinik

**Stand:** 29. Mai 2026
**Zweck:** Funktionsübersicht für die Klinikleitung der Universitätsklinik für Augenheilkunde und Optometrie an der MedUni Wien.

---

## 1. Worum es geht

LibreClinica MUW wird das institutionelle System für die elektronische Erfassung klinischer Studiendaten (eCRF) der Klinik. Es dokumentiert jede Datenänderung GCP- und 21-CFR-Part-11-konform und unterstützt mehrere Studienorte parallel. Die Plattform basiert auf der Open-Source-Software LibreClinica (LGPL-lizenzierter Nachfolger von OpenClinica 3.14) und wird als institutioneller Fork weiterentwickelt.

Damit sich das System nahtlos in die MedUni-Wien-Infrastruktur einfügt, wird die Oberfläche an das institutionelle Corporate Design (Stand März 2022) angepasst und die Anmeldung an den **Shibboleth-Single-Sign-on** der MedUni Wien gekoppelt. Klinische Mitarbeitende melden sich mit ihren MedUni-Credentials an; Passwort, 2-Faktor-Verifikation und Lockout verbleiben bei der MedUni-Wien-IT.

Die technische Modernisierung der Plattform läuft in mehreren Phasen; die hier gezeigten Bildschirmentwürfe entstehen in der letzten Phase und werden nach Fertigstellung der vorhergehenden Phasen umgesetzt.

## 2. Was die Plattform leistet

Drei Hauptrollen — **Prüfarzt/-ärztin**, **Studien-Monitor** und **Datenmanager/-in** — decken alle in einer klinischen Studie anfallenden Tätigkeiten ab. Die folgenden Abschnitte zeigen je einen typischen Arbeitsablauf.

![Startseite](images/01-index.png)

*Interne Startseite der Bildschirmentwürfe, gruppiert nach Rolle und Themenrunde.*

### 2.1 Prüfarzt / Prüferin

Die studienbezogene Arbeit in der Ambulanz: Studienteilnehmer aufnehmen, Termine planen, CRF-Daten eingeben, Rückfragen beantworten und Studienteilnehmer unterschreiben.

![Subject Matrix — die tägliche Arbeitsoberfläche](images/03-investigator-subject-matrix.png)

*Subject Matrix. Standortbezogene Liste aller Studienteilnehmer mit Status pro Visite. Ein Klick führt zur Datenerfassung oder zur Visitenplanung.*

![CRF-Erfassung mit eingebetteten Rückfragen](images/04-investigator-crf-entry.png)

*CRF-Datenerfassung. Sektionsweise gegliederte Eingabe, deutliche Markierung von Pflichtfeldern, eingebettete Rückfragen-Threads — kein Wechsel in Popup-Fenster notwendig.*

### 2.2 Monitor — Source Data Verification

Die Qualitätssicherung: ausgefüllte CRFs gegen Quelldokumente prüfen, Rückfragen stellen und schließen.

![SDV-Tabelle mit Bulk-Verifikation](images/05-monitor-sdv.png)

*Source Data Verification. Alle abgeschlossenen CRFs aller Studienteilnehmer in einer prüfbaren Übersicht, gefiltert nach SDV-Anforderung und Status, mit Bulk-Aktion zur Mehrfach-Verifikation.*

### 2.3 Datenmanager/-in

Der Studienaufbau: CRF-Design, Visitendefinitionen, Validierungsregeln, Nutzerverwaltung.

![CRF-Designer mit Live-Vorschau](images/07-dm-create-crf.png)

*Create / Edit CRF. Editor mit drei Bereichen: Sektionsnavigation links, inline-editierbares Item-Raster mittig, Eigenschafts-Panel rechts. Unten eine Live-Vorschau aus Sicht des Prüfarztes — die Iterationskosten beim Studienaufbau sinken deutlich.*

### 2.4 Rollenübergreifend — Audit Trail

![Audit Trail mit Vorher/Nachher-Vergleich](images/06-study-audit-log.png)

*Study Audit Log. Lückenlose Zeitleiste aller Aktionen — Datenänderungen, SDV, Unterschriften, administrative Eingriffe. Diff-Karten zeigen Vorher/Nachher-Werte bei „Reason for Change"-Edits.*
