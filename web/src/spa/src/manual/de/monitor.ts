import type { ManualChapter } from '../manualTypes'

export const monitorDe: ManualChapter = {
  id: 'monitor', role: 'monitor', kicker: 'Kapitel 03',
  title: 'Monitor', deutsch: 'Monitor',
  oneLiner: 'Quelldatenvergleich, Rückfragen/Diskrepanzen, schreibgeschützte Durchsicht, Audit Trail.',
  intro: [
    'Der **Monitor** ist die schreibgeschützte Aufsichtsrolle der Studie. Ein Monitor prüft Daten, die andere Rollen erfasst haben, gleicht sie gegen die Quelldokumente ab (Source Data Verification, *Quelldatenvergleich*), stellt und schließt Diskrepanznotizen (Queries / *Rückfragen*) und sieht den unveränderlichen Audit Trail ein. Ein Monitor **kann keine klinischen Daten erfassen oder ändern** — jedes CRF, das ein Monitor öffnet, ist schreibgeschützt.',
  ],
  callout: {
    kind: 'accent', title: 'Zwei Befugnisse hat allein der Monitor',
    text: '**Quelldatenvergleich** — das Markieren von CRFs als verifiziert (SDV) und das Zurücknehmen dieser Markierung, sobald sich die zugrunde liegenden Daten ändern. **Eine Rückfrage schließen** — nur ein Monitor (oder ein Data Manager / Administrator per Override) kann eine Rückfrage in ihren Endzustand *Geschlossen* (Closed) überführen.',
  },
  sections: [
    {
      id: 'mo-home', num: '1', title: 'Start', deutsch: 'Home dashboard', route: '/',
      roles: ['monitor'],
      shot: 'monitor/00-home.png',
      goal: 'Sehen Sie, was Ihre Aufmerksamkeit erfordert, und springen Sie in Ihre häufigsten Monitor-Aufgaben.',
      steps: [
        'Nach der Anmeldung (und der Auswahl einer Studie) landen Sie auf dem **Start-Dashboard**.',
        'Lesen Sie die Aufmerksamkeitsübersicht — Ihnen zugewiesene Notizen und Diskrepanzen sowie die Anzahl der CRFs, die noch auf eine Verifizierung warten.',
        'Nutzen Sie die Seitennavigation oder die Schnellzugriffe des Dashboards, um **Quelldatenvergleich**, **Rückfragen & Diskrepanzen** oder den **Audit Trail** zu öffnen.',
      ],
      notes: [
        'Sie können jederzeit über **Start** (Home) hierher zurückkehren. Das Dashboard ist schreibgeschützt; es verlinkt in die Arbeitsmasken, statt ein direktes Bearbeiten der Daten zu erlauben.',
      ],
    },
    {
      id: 'mo-matrix', num: '2', title: 'Studienteilnehmer', deutsch: 'Subject Matrix', route: '/subjects',
      roles: ['investigator','monitor','data-manager','administrator'],
      shot: 'monitor/01-subject-matrix.png',
      goal: 'Finden Sie eine/n Teilnehmer/in und öffnen Sie deren Visiten und CRFs zur visuellen Durchsicht.',
      steps: [
        'Öffnen Sie **Studienteilnehmer** über die Seitennavigation.',
        'Nutzen Sie das Suchfeld (*Teilnehmer per ID suchen…*) oder die Filter-Chips — **Alle**, **Mit offenen Visiten**, **Alle Visiten abgeschlossen**, **Signiert** und die Checkbox **Nur mit offenen Rückfragen**.',
        'Jede Zeile zeigt Teilnehmer-ID, Geschlecht, Studienauge, Gruppe, Aufnahmedatum und eine Status-Pille je Visite. Ein kleines rotes Abzeichen neben einer Visiten-Pille zählt die offenen Rückfragen darauf.',
        'Klicken Sie auf die Teilnehmer-ID oder rechts auf **Öffnen**, um in das Teilnehmerdetail und von dort in einzelne Visiten und CRFs zu wechseln.',
      ],
      notes: [
        'Für den Monitor ist diese Maske ein **schreibgeschütztes Nachschlagewerkzeug** — es gibt kein Stift- / Dateneingabe-Symbol und keine Aktion *Teilnehmer aufnehmen* (Add Subject).',
        'Die Visitenspalten scrollen horizontal; nutzen Sie die Pfeil-Schaltflächen oder **Letzte Visite**, um durch lange Visitenzeitleisten zu blättern, während die Teilnehmer- und Aktionsspalten fixiert bleiben.',
      ],
    },
    {
      id: 'mo-sdv', num: '3', title: 'Quelldatenvergleich', deutsch: 'Source Data Verification (SDV)', route: '/sdv',
      roles: ['monitor'],
      shot: 'monitor/02-sdv.png',
      goal: 'Bestätigen Sie, dass die in der Anwendung erfassten Daten mit den Quelldokumenten übereinstimmen, und markieren Sie anschließend jedes verifizierte CRF als Verifiziert. Dies ist der zentrale Monitor-Workflow.',
      steps: [
        'Öffnen Sie **Quelldatenvergleich** (`/sdv`). Die Tabelle listet jedes zur Verifizierung bereite CRF auf, eine Zeile je CRF, mit Teilnehmer, Zentrum, Visite, Visitendatum, CRF-Name, **Anforderung** (SDV-Anforderung) und **Status**.',
        'Grenzen Sie über die Filterzeile ein: das Suchfeld, das Dropdown **Status** (**Alle Status**, **Ausstehend**, **Rückfrage**, **Verifiziert**, **Gesperrt**), das Dropdown **Anforderung** (**100 % erforderlich**, **Teilweise erforderlich**, **Nicht erforderlich**) und die Checkbox **Nur mit offenen Rückfragen**. Mit **Zurücksetzen** leeren Sie die Filter.',
        'Öffnen Sie ein CRF, um es gegen die Quelle abzugleichen: Klicken Sie auf **CRF öffnen**. Das CRF öffnet **schreibgeschützt** — Eingaben werden angezeigt, können aber nicht gespeichert werden.',
        'Stimmt ein CRF mit der Quelle überein, markieren Sie es als verifiziert: eine Zeile über ihre Checkbox + die Sammel-Leiste, oder mehrere auf einmal über die Kopf-Checkbox und dann **… als verifiziert markieren (SDV)**. Ein Bestätigungsdialog zeigt die Anzahl an.',
        'Bestätigen Sie im Dialog **Als verifiziert markieren?**. Die Aktion wird mit Ihrem Benutzernamen und einem Zeitstempel im Audit Trail festgehalten.',
      ],
      notes: [
        '**Nur CRFs im Status *Ausstehend* (Pending) können** zur Verifizierung ausgewählt werden; die Checkbox ist bei Zeilen deaktiviert, die bereits verifiziert oder gesperrt sind oder eine offene Rückfrage tragen.',
        '**Die SDV-Anforderung ist hier nur eine Anzeige.** Ob ein CRF keine, teilweise oder 100 % Verifizierung benötigt, legt der Data Manager beim Studienaufbau fest.',
        '**Die Verifizierung kann zurückgenommen werden.** Nutzen Sie in einer *Verifiziert*-Zeile **Verifizierung zurücknehmen** — Sie müssen eine **Begründung** (reason) angeben; die Rücknahme wird dokumentiert und das CRF kehrt in die Warteschlange der ausstehenden zurück.',
        '**Automatische Rücksetzung:** Werden Daten eines verifizierten CRFs später geändert, wechselt sein SDV-Status von selbst zurück auf **Ausstehend**.',
      ],
      sub: {
        title: '3.1 Schreibgeschützte CRF-Durchsicht',
        text: 'Während der Verifizierung öffnen Sie jedes CRF schreibgeschützt unter `/event-crfs/:eventCrfOid/readonly` (über **CRF öffnen** in einer SDV-Zeile oder den Item-Link einer Diskrepanz). Das CRF wird genauso dargestellt wie bei der Dateneingabe — Kopfinformationen, Abschnitts-Tabs und Items — aber es gibt **keine Speichern-Aktion**. Dies ist die Maske, auf der Sie jedes Item gegen das Quelldokument abgleichen und, wo Sie eine Abweichung finden, eine Rückfrage stellen.',
      },
    },
    {
      id: 'mo-notes', num: '4', title: 'Rückfragen & Diskrepanzen', deutsch: 'Notes & Discrepancies (queries)', route: '/notes',
      roles: ['data-manager','monitor','administrator'],
      shot: 'monitor/03-notes-discrepancies.png',
      goal: 'Stellen Sie eine Rückfrage, wenn erfasste Daten nicht mit der Quelle übereinstimmen, verfolgen Sie den Austausch und schließen Sie die Rückfrage, sobald sie geklärt ist.',
      steps: [
        'Öffnen Sie **Rückfragen & Diskrepanzen** (`/notes`). Die Übersichtskarten zählen die offenen Einträge nach Typ: **Rückfrage** (Query), **Fehlgeschlagene Validierung**, **Notiz** und **Änderungsgrund**.',
        'Filtern Sie mit dem Suchfeld, dem Dropdown **Status** (standardmäßig **Nur offen**, zusätzlich **Neu**, **Aktualisiert**, **Lösung vorgeschlagen**, **Geschlossen**, **Nicht zutreffend**), dem Dropdown **Typ** und der Checkbox **Mir zugewiesen**.',
        '**Eine Rückfrage stellen.** Öffnen Sie während der SDV-Durchsicht das CRF schreibgeschützt und nutzen Sie **Rückfrage stellen** am betreffenden Item. Im Dialog **Anmerkung hinzufügen** ist der Typ für einen Monitor fest auf **Rückfrage** gesetzt; geben Sie eine **Beschreibung** ein und dann **Rückfrage absenden**.',
        '**Den Verlauf verfolgen.** Klicken Sie auf den Pfeil einer Zeile, um deren Verlauf aufzuklappen. Der erfassende Benutzer antwortet (**Antworten**) und schlägt eine Lösung vor (**Als gelöst markieren** → *Lösung vorgeschlagen*).',
        '**Die Rückfrage schließen.** Sobald Sie das CRF erneut geprüft haben und der Wert korrekt ist, klicken Sie in einer *Lösung vorgeschlagen*-Zeile auf **Schließen**. Ein Schlusskommentar ist optional. Die Rückfrage wechselt in den Status **Geschlossen** — ihren Endzustand.',
      ],
      notes: [
        '**Lebenszyklus (Sicht des Monitors):** **Neu** → **Aktualisiert** → **Lösung vorgeschlagen** → **Geschlossen**. Eine Notiz wird nie gelöscht — es ändert sich nur ihr Status.',
        '**Wer macht was:** Der Monitor *stellt* und *schließt*; die Schaltflächen **Antworten** und **Als gelöst markieren** werden dem Prüfarzt / der CRC angezeigt. **Schließen** erscheint nur in Zeilen mit Status *Lösung vorgeschlagen*.',
        '**Der Typ ist für den Monitor fest** — ein Monitor erstellt **Rückfrage**-Notizen. Ein Monitor sieht **alle** Diskrepanzen der Studie; nutzen Sie **Mir zugewiesen**, um sich zu fokussieren. Mit **CSV exportieren** laden Sie die gefilterte Liste herunter.',
      ],
    },
    {
      id: 'mo-audit', num: '5', title: 'Audit Trail der Studie', deutsch: 'Study Audit Log', route: '/audit-log',
      roles: ['data-manager','monitor','administrator'], tall: true,
      shot: 'monitor/04-study-audit-log.png',
      goal: 'Sehen Sie die vollständige, unveränderliche Änderungshistorie ein — für die laufende Aufsicht und die Inspektionsbereitschaft bei Sponsor-Audits.',
      steps: [
        'Öffnen Sie **Audit Trail** (`/audit-log`).',
        'Die Einträge erscheinen auf einer Zeitleiste, gruppiert nach Datum (**Heute**, **Gestern**, danach explizite Datumsangaben). Jede Zeile nennt die Aktion, den/die Teilnehmer/in, den Geltungsbereich, den/die Akteur/-in und dessen/deren Rolle sowie die Uhrzeit.',
        'Grenzen Sie über die Filterzeile ein: **Akteur/-in**, **Ereignistyp** (**Signatur**, **Änderungsgrund**, **SDV**, **Administration**, **Datenerfassung**, **Rückfrage**, **Gruppenwechsel**) und **Teilnehmer**. Mit **Zurücksetzen** setzen Sie zurück.',
        'Klicken Sie auf eine Zeile mit Pfeil, um sie aufzuklappen und den alten Wert / neuen Wert nebeneinander samt etwaiger Begründungsnotiz zu sehen.',
      ],
      notes: [
        'Der Audit Trail ist **schreibgeschützt und unveränderlich** — Filter grenzen nur die Ansicht ein. Verfügbar für Monitor, Data Manager und Administrator; Prüfärzte/-ärztinnen sehen ihn nicht.',
        'Nutzen Sie **XLSX exportieren**, um die gefilterte Ansicht für eine Offline-Durchsicht herunterzuladen oder einem Sponsor zu übergeben.',
      ],
    },
    {
      id: 'mo-datasets', num: '6', title: 'Datenexport', deutsch: 'Datasets / Data Export', route: '/datasets',
      roles: ['data-manager','monitor','administrator'],
      shot: 'monitor/05-datasets.png',
      goal: 'Führen Sie einen gespeicherten Datenexport für die aktive Studie aus und laden Sie die erzeugten Dateien herunter.',
      steps: [
        'Öffnen Sie **Datenexport** (`/datasets` oder `/export`). Die Tabelle listet die für die aktive Studie gespeicherten Datensätze auf, mit Eigentümer/-in, Erstellungsdatum, letztem Lauf und Dateianzahl.',
        'Für einen schnellen Export der gesamten Studie nutzen Sie **Schnell-ODM-Export**.',
        'Um einen gespeicherten Datensatz auszuführen, klicken Sie auf **Jetzt exportieren**, wählen ein Format (**ODM (CDISC XML)**, **CSV**, **TSV**, **Excel**, **SAS**, **SPSS**) und starten den Export.',
        'Klicken Sie in einer Zeile auf **Dateien anzeigen**, um die erzeugten Dateien aufzuklappen und einen früheren Lauf mit **Herunterladen** herunterzuladen.',
      ],
      notes: [
        'Ein Monitor kann Exporte ausführen und herunterladen, einschließlich der von anderen Benutzern der Studie erstellten Datensätze (die rollenübergreifende Sichtbarkeit ist für das Monitoring beabsichtigt).',
        'Neue Datensätze werden über den klassischen Assistenten **Extract Data** definiert; einmal gespeichert, erscheinen sie in dieser Liste und können aus der SPA erneut ausgeführt werden.',
      ],
    },
  ],
}
