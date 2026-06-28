import type { ManualChapter } from '../manualTypes'

export const crcDe: ManualChapter = {
  id: 'crc', role: 'crc', kicker: 'Kapitel 05',
  title: 'Koordinator/-in', deutsch: 'CRC (Clinical Research Coordinator)',
  oneLiner: 'Tägliche Dateneingabe; erbt die Investigator-Oberfläche — signiert jedoch keine Fallbücher.',
  intro: [
    'Die **CRC** (Clinical Research Coordinator) ist die Rolle für die tägliche Dateneingabe: Aufnahme neuer Teilnehmer, Öffnen ihrer geplanten Visiten und Erfassen von CRF-Daten — einschließlich gepaarter ophthalmologischer Items für das rechte/linke Auge (OD/OS).',
    'In LibreClinicaMUW **erbt die CRC die Investigator-Oberfläche**. Es gibt kein separates, umfangreiches CRC-Menü: Die Bildschirme, die Sie nutzen, sind die Investigator-Bildschirme — die Studienteilnehmer-Matrix, Teilnehmer aufnehmen, das Fallbuch des Teilnehmers, die Ereignis-Detailansicht und das CRF-Eingabeformular.',
  ],
  callout: {
    kind: 'warn', title: 'Eine Aufgabe übernimmt die CRC nicht: das Signieren von Fallbüchern',
    text: 'Das Anbringen der elektronischen Signatur am Fallbuch eines Teilnehmers ist die Bestätigung des Investigators und wird mit dem eigenen Passwort des signierenden Investigators eingegeben. Sie bereiten die Daten so vor, dass sie vollständig und bereit sind; der Investigator signiert. Den Signier-Workflow finden Sie im Kapitel Investigator.',
  },
  sections: [
    {
      id: 'crc-matrix', num: '1', title: 'Studienteilnehmer', deutsch: 'Find a subject (Subject Matrix)', route: '/subjects',
      roles: ['crc','investigator'],
      shot: 'crc/01-subject-matrix.png',
      shotPre: 'crc/00-home.png',
      shotPreCaption: 'Der CRC-Startbildschirm — die heutigen Aufgaben und Schnellzugriffe auf Ihre häufigen Arbeitsabläufe.',
      goal: 'Einen Teilnehmer auffinden und sehen, welche seiner Visiten offen, abgeschlossen oder signiert sind.',
      steps: [
        'Öffnen Sie **Studienteilnehmer** über die Seitennavigation (`/subjects`).',
        'Nutzen Sie das **Suchfeld**, um nach Teilnehmer-ID zu filtern, oder verwenden Sie die Filter-Chips — **Alle**, **Heute**, **Bereit zum Signieren**, **Offene Visiten**, **Alle abgeschlossen** und **Signiert**.',
        'Lesen Sie die Zeile: Die Spalte **Auge** zeigt das Studienauge (OD / OS / OU), und jede Visitenspalte zeigt ein Status-Pill, mit einer roten Zahl, wenn diese Visite offene Rückfragen hat.',
        'Klicken Sie auf die Teilnehmer-ID oder rechts auf **Öffnen**, um den Teilnehmer zu öffnen.',
      ],
      notes: [
        'Die Visitenspalten scrollen **innerhalb** der Tabelle — nutzen Sie die Chevron-Schaltflächen oder **Zur letzten Visite springen**, um durch eine lange Visiten-Zeitleiste zu gleiten. Die Spalten **Studienteilnehmer** und **Öffnen** bleiben fixiert.',
        'Die Matrix öffnet sich nach links gescrollt, sodass Sie zuerst die Identität sehen (ID, Geschlecht, Auge, Gruppe, Aufnahmedatum) und dann die Visiten.',
      ],
    },
    {
      id: 'crc-add', num: '2', title: 'Teilnehmer aufnehmen', deutsch: 'Enrol a subject (Add Subject)', route: '/subjects/new',
      roles: ['crc','investigator'],
      shot: 'crc/02-add-subject.png',
      shot2: 'crc/02b-add-subject-filled.png',
      shot2Caption: 'Das Aufnahmeformular mit Beispieldaten ausgefüllt, vor dem Speichern.',
      goal: 'Einen neuen Teilnehmer zur aktiven Studie hinzufügen.',
      steps: [
        'Klicken Sie in der Seitennavigation auf **Add Subject** (Teilnehmer aufnehmen) oder auf der Matrix auf **+** (`/subjects/new`).',
        'Geben Sie die **Subject ID** (Teilnehmer-ID) ein. Wenn die Studie einen Protokoll-Kurzcode besitzt, ist das Feld vorausgefüllt (z. B. `GA-`). Die Anwendung prüft während der Eingabe die Verfügbarkeit und markiert eine bereits vergebene ID.',
        'Fügen Sie optional eine **Secondary ID** (Sekundär-ID) hinzu. Geben Sie hier **keine** direkten Patientenidentifikatoren ein — das Formular warnt vor personenbezogenen Daten.',
        'Legen Sie das **Aufnahmedatum** fest (darf nicht in der Zukunft liegen) und wählen Sie das **Geschlecht**.',
        'Unter **Ophthalmologie** können Sie optional das **Studienauge** (OD / OS / OU) und ein **Screening-Datum** festlegen. Lassen Sie diese Angaben bei nicht-ophthalmologischen Studien leer.',
        'Speichern Sie mit **Save & add next** (Speichern & nächsten hinzufügen), **Save & finish** (Speichern & abschließen) oder **Save & schedule** (Speichern & planen — primär; öffnet den neuen Teilnehmer, um die erste Visite zu planen).',
      ],
      notes: [
        'Der Standort wird aus Ihrer aktiven Studien-/Standortauswahl übernommen — Sie wählen ihn nicht in diesem Formular.',
        'Wenn der Server die Übermittlung ablehnt (zum Beispiel bei einer doppelten ID an diesem Standort), wird der genaue Feldfehler inline angezeigt; korrigieren Sie ihn und speichern Sie erneut.',
      ],
    },
    {
      id: 'crc-entry', num: '3', title: 'Datenerfassung', deutsch: 'Open a visit and enter CRF data', route: '/event-crfs/:oid',
      roles: ['crc','investigator'],
      shot: 'crc/20-subject-detail.png',
      goal: 'Daten für eine der geplanten Visiten eines Teilnehmers erfassen, einschließlich gepaarter OD/OS-Items, dann speichern und das CRF als abgeschlossen markieren.',
      steps: [
        'Öffnen Sie vom Fallbuch des Teilnehmers aus die Visite (das Ereignis), an der Sie arbeiten möchten — der Bildschirm **Ereignis-Detail** listet die CRFs auf, die zu dieser Visite gehören.',
        'Wählen Sie für jede CRF-Zeile rechts die Aktion: **Öffnen**, wenn die Dateneingabe bereits begonnen hat, oder **Datenerfassung starten**, um ein neues CRF zu beginnen.',
        'Füllen Sie die Items aus. Bei **bilateralen** Items zeigt das Formular zwei Spalten: **OD LINKS** (Kennzeichen **R**), **OS rechts** (Kennzeichen **L**). Für beide Augen (OU) gekennzeichnete Items erstrecken sich als einzelnes Feld über beide Spalten.',
        'Klicken Sie jederzeit auf **Entwurf speichern** (save draft); die Kopfzeile zeigt an, wann zuletzt gespeichert wurde, und warnt vor ungespeicherten Änderungen.',
        'Wenn das CRF fertig ist, klicken Sie auf **Abschließen** (mark complete). Pflichtfelder werden validiert; alles Fehlende wird hervorgehoben. Nach dem Abschluss kehren Sie zur Visite zurück.',
        'Zurück auf dem Bildschirm Ereignis-Detail klicken Sie, wenn alle CRFs der Visite erledigt sind, auf **Visite abschließen**, um die gesamte Visite als abgeschlossen zu markieren.',
      ],
      notes: [
        'Ein **abgeschlossenes** CRF wird schreibgeschützt. Um es erneut zu bearbeiten, klicken Sie auf **Erneut öffnen** (reopen) — als CRC dürfen Sie ein abgeschlossenes CRF erneut öffnen; dies wird im Audit-Trail festgehalten.',
        'Ein CRF, das **gesperrt** ist (weil das Fallbuch des Teilnehmers signiert wurde), kann von hier aus nicht erneut geöffnet werden — das erfordert die Studienleitung.',
        'Nutzen Sie **+ Frage** an einem Item, um eine Diskrepanz-Notiz zu erstellen, und **Vom letzten Besuch übernehmen**, um fortgeschriebene Werte aus der vorherigen Visite vorauszufüllen (Sie prüfen sie weiterhin und **speichern** sie bewusst).',
        'Jede Eingabe wird Ihnen im Audit-Trail zugeordnet — melden Sie sich an einer gemeinsam genutzten Arbeitsstation immer ab, wenn Sie sich entfernen.',
      ],
    },
  ],
}
