import type { ManualChapter } from '../manualTypes'

export const dataManagerDe: ManualChapter = {
  id: 'data-manager', role: 'data-manager', kicker: 'Kapitel 02',
  title: 'Studienleitung', deutsch: 'Data Manager',
  oneLiner: 'Baut die Studie auf und betreibt sie: CRFs, Visiten, Regeln, Gruppen, Diskrepanz-Aufsicht, Datenexport.',
  intro: [
    'Die **Studienleitung** (Data Manager) baut die Studie auf und hält ihre Daten sauber. Dies ist die umfassendste Rolle auf Studienebene: Sie stellen die CRFs zusammen, definieren die Visiten, hängen Validierungsregeln an, gruppieren Teilnehmende für die Auswertung, überwachen Diskrepanzen und exportieren die Daten. Die tägliche Daten*erfassung* ist Aufgabe der Prüfärztin bzw. des Prüfarztes — als Studienleitung richten Sie die Studie ein, beaufsichtigen sie und ziehen die Daten heraus.',
  ],
  callout: {
    kind: 'info', title: 'Was Sie erreichen können und was nicht',
    text: 'Sie können die **Studienteilnehmer** (Subject Matrix) öffnen, um Aufnahme und Visitenfortschritt einzusehen, aber der teilnehmerbezogene Detailbildschirm und die direkte CRF-Datenerfassung sind Bildschirme der Prüfärztin/des Prüfarztes bzw. der Administration. Das Anlegen von Benutzerkonten, das Bearbeiten der Kernidentität der Studie sowie das Sperren/Entsperren einzelner Visiten oder CRF-Versionen sind Administrations-Aktionen. Ihre Aufsichtsbefugnisse — Diskrepanzen, Audit Trail, Regeln, Export, Abgleich der Doppeldateneingabe — stehen Ihnen alle zur Verfügung.',
  },
  sections: [
    {
      id: 'dm-home', num: '1', title: 'Start', deutsch: 'Home', route: '/',
      roles: ['data-manager'],
      shot: 'data-manager/00-home.png',
      goal: 'Offene Diskrepanzen einsehen und zu Ihren häufigen Aufgaben springen.',
      steps: [
        'Nach der Anmeldung (und der Auswahl einer Studie) landen Sie auf dem Start-Dashboard.',
        'Sehen Sie die Übersicht der Ihnen zugewiesenen Notizen und Diskrepanzen durch.',
        'Nutzen Sie die Seitennavigation oder die Dashboard-Links, um eine Aufgabe zu beginnen — die meiste Aufbauarbeit beginnt bei **Studienaufbau** (Build Study).',
      ],
      notes: [
        'Die obere Leiste zeigt die aktive Studie/den aktiven Standort, Ihren Namen mit einem farbcodierten **Rollen-Chip**, die Sprachanzeige und **Abmelden**.',
      ],
    },
    {
      id: 'dm-matrix', num: '2', title: 'Studienteilnehmer', deutsch: 'Subject Matrix', route: '/subjects',
      roles: ['investigator','monitor','data-manager','administrator'],
      shot: 'data-manager/01-subject-matrix.png',
      goal: 'Aufnahme und Status je Visite über alle Teilnehmenden der Studie hinweg überprüfen.',
      steps: [
        'Öffnen Sie **Studienteilnehmer** (Subject Matrix) über die Seitennavigation.',
        'Jede Zeile ist eine teilnehmende Person; die Spalten zeigen **Geschlecht**, **Studienauge** (OD/OS/OU), **Gruppe**, **Aufnahmedatum**, eine Zelle je Visite sowie eine **Signiert**-Anzeige.',
        'Filtern Sie über das Suchfeld oder die Status-Chips, oder setzen Sie das Häkchen bei **nur mit Rückfragen**, um Teilnehmende mit offenen Diskrepanzen zu finden.',
        'Bei Studien mit vielen Visiten nutzen Sie die Pfeilschaltflächen oder **Zur aktuellsten Visite springen**, um durch die Visitenspalten zu scrollen; die Teilnehmerspalte bleibt links fixiert.',
        'Öffnen Sie **Studien-Statistik** (study metrics) über die Seitenleiste für aggregierte Zählungen.',
      ],
      notes: [
        'Ein rotes Abzeichen auf einer Visitenzelle ist die Anzahl der **offenen Rückfragen** (open queries) zu dieser Visite — Ihr Hinweis, in Rückfragen & Diskrepanzen nachzufassen.',
        'Der Teilnehmer-Link und die Aktion **Öffnen** verweisen auf das teilnehmerbezogene Casebook, einen Bildschirm der Prüfärztin/des Prüfarztes bzw. der Administration. Als Studienleitung nutzen Sie die Matrix, um den Fortschritt zu *überwachen*; Sie erfassen oder signieren hier keine Daten.',
      ],
    },
    {
      id: 'dm-build', num: '3', title: 'Studienaufbau', deutsch: 'Build Study', route: '/build-study',
      roles: ['administrator','data-manager'],
      shot: 'data-manager/02-build-study.png',
      goal: 'Die Aufgaben verfolgen und abschließen, die eine Studie nutzbar machen.',
      steps: [
        'Öffnen Sie **Studienaufbau** (Build Study). Der Kopfbereich zeigt eine **Fortschritts**-Karte sowie die Zählungen **Standorte** und **aufgenommene Teilnehmende**.',
        'Arbeiten Sie die nummerierte Aufgabenliste ab — **CRFs**, **Visiten-Definitionen** (Event Definitions), **Teilnehmer-Gruppen** (Group Classes), **Regeln** (Rules) (und, für Administrationen, Studie anlegen / Standorte / Benutzer). Jede Karte zeigt eine Status-Pille und eine einzeilige Zusammenfassung.',
        'Klicken Sie auf **Weiter →** bei einer Aufgabe, um zu dem Bildschirm zu springen, der sie abschließt.',
        'Für die optionalen Aufgaben, zu denen es legitim nichts hinzuzufügen gibt, nutzen Sie **Als abgeschlossen markieren**, um sie zu bestätigen.',
      ],
      notes: [
        'Das Dropdown **Studienstatus setzen** (Set Study Status) sowie die Schaltflächen **Studie bearbeiten** / **Studienparameter** / **Studie anlegen** sind **nur für Administrationen**. Ein Übergang zum Sperren/Einfrieren fragt nach einem Grund (im Audit Trail erfasst).',
        'Die Aufgabe „Studie anlegen“ zeigt für Nicht-Administrationen einen Hinweis im Nur-Lesen-Modus; der Rest des Trackers gehört Ihnen.',
      ],
    },
    {
      id: 'dm-events', num: '4', title: 'Visiten-Definitionen', deutsch: 'Event Definitions', route: '/event-definitions',
      roles: ['administrator','data-manager'],
      shot: 'data-manager/03-event-definitions.png',
      goal: 'Die Visiten der Studie definieren und festlegen, was bei jeder Visite erhoben wird.',
      steps: [
        'Öffnen Sie **Visiten-Definitionen** (Event Definitions) (oder **Weiter →** aus der Visiten-Aufgabe im Studienaufbau).',
        'Klicken Sie auf **Anlegen**, um eine Visite hinzuzufügen: Geben Sie einen **Namen** ein, wählen Sie einen **Typ** (*geplant* / *ungeplant* / *allgemein*), optional eine **Kategorie** und eine **Beschreibung**, und setzen Sie das Häkchen bei **wiederholbar**, wenn die Visite mehr als einmal stattfinden kann.',
        'Ordnen Sie Visiten mit den **↑ / ↓**-Pfeilen um; die Reihenfolge legt fest, wie die Visiten dem Erfassungspersonal erscheinen.',
        'Nutzen Sie **CRFs verwalten** (Manage CRFs), um CRFs an die Visite anzuhängen. Im CRF-Zuweisungsdialog wird die **SDV-Anforderung** je CRF gesetzt — der Wert, auf den Monitore später hin tätig werden.',
        'Für OCT-Bildgebungs-Visiten zeigt das Bearbeitungsformular ein Panel für **Netzhaut-Inferenz-Tasks** (*fluid*, *ga*, *onl*, *pr*, *layers*) — wählen Sie, welche automatisierten Jobs ausgeführt werden, wenn ein Scan zu dieser Visite festgeschrieben wird.',
        '**Deaktivieren** entfernt eine Visite; schalten Sie **entfernte anzeigen** ein, um eine wieder**herzustellen**.',
      ],
      notes: [
        'Anlegen, Bearbeiten, Umordnen, Deaktivieren, Wiederherstellen und die CRF-Zuweisung stehen der Studienleitung zur Verfügung. Das **Sperren / Entsperren** einer Visiten-Definition ist **nur der Administration vorbehalten**.',
        'Die Semantik von Wiederholbarkeit + Typ steuert, wie sich Studienteilnehmer und Terminplanung verhalten — setzen Sie sie bewusst.',
      ],
    },
    {
      id: 'dm-crf', num: '5', title: 'CRF-Bibliothek', deutsch: 'CRF Library + CRF Builder', route: '/crf-library',
      roles: ['administrator','data-manager'],
      shot: 'data-manager/04-crf-library.png',
      goal: 'Case Report Forms erstellen und ihre Versionen erstellen.',
      steps: [
        'Öffnen Sie **CRF-Bibliothek** (CRF Library). Jede Karte ist ein CRF mit seinen Versionen inline aufgelistet (Name, OID, Status).',
        'Klicken Sie auf **Neuen CRF anlegen**, um eine CRF-Hülle hinzuzufügen — geben Sie ihr einen **Namen** und optional eine **Beschreibung**.',
        'Um eine Formularversion zu erstellen, nutzen Sie **manuell anlegen** auf der CRF-Karte. Dies öffnet die Arbeitsfläche des **CRF Builder** (`/crf-authoring-canvas/:crfOid`): ein dreispaltiger Drag-and-drop-Editor — Item-Palette, Abschnitts-Arbeitsfläche, Eigenschaften je Item. **Vorschau** stellt das Formular dar; das Speichern schreibt eine neue Version.',
        'Um von einer früheren Version auszugehen, nutzen Sie den Pfeil neben **manuell anlegen** und wählen Sie **fork from** dieser Version.',
        'Pro Version können Sie das Excel (.xls) **herunterladen** sowie die Version **sperren / entsperren** und **deaktivieren / wiederherstellen**.',
        'Setzen Sie das Häkchen bei **entfernte anzeigen**, um deaktivierte CRFs zu sehen und wiederherzustellen.',
      ],
      notes: [
        'Die Drag-and-drop-**Arbeitsfläche** (canvas) ist die primäre Erstellungsoberfläche; der herkömmliche Excel-Upload besteht weiterhin für Round-Trips mit Sponsor-Arbeitsmappen, ist aber nicht mehr der vorrangige Weg.',
        'Das **Hart-Entfernen** (Hard-remove) einer Version ist **nur der Administration vorbehalten**; wird eine Version noch von Visiten-Definitionen oder erfassten Daten referenziert, zeigt die SPA einen Blocker-Bericht an, statt zu löschen.',
      ],
    },
    {
      id: 'dm-rules', num: '6', title: 'Regeln', deutsch: 'Rules', route: '/rules',
      roles: ['administrator','data-manager'],
      shot: 'data-manager/05-rules.png',
      goal: 'Validierungs- und Automatisierungsregeln anhängen und sie testen, bevor sie laufen.',
      steps: [
        'Öffnen Sie **Regeln** (Rules). Die linke Liste zeigt jedes Regelziel; die Auswahl eines Ziels öffnet dessen Detailbereich (Ziel, Visiten-Definition, CRF/Version, angehängte Regeln, Lauf-Protokoll).',
        'Klicken Sie auf **Neue Regel**, um eine Regel mit dem 3-Schritt-Assistenten zu erstellen (Regelkörper → Ziel + Geltungsbereich → Aktion), oder auf **Regeln importieren**, um eine XML-Regeldefinition hochzuladen.',
        'Im Detailbereich bearbeiten Sie eine Regel oder ihre Aktion inline und legen den **Lauf-Zeitplan** (eine tägliche Batch-Zeit) über den Zeitplan-Editor fest.',
        'Nutzen Sie **Probelauf** (dry run), um vorab zu sehen, welche Teilnehmenden/Aktionen eine Regel träfe, ohne etwas zu persistieren, und **als XML exportieren**, um die ausgewählten Regeln herunterzuladen.',
        'Nutzen Sie das Panel **Test**, um einen rohen Ausdruck gegen ad-hoc eingegebene Schlüssel/Wert-Testdaten auszuwerten.',
      ],
      notes: [
        'Anlegen / Importieren / Bearbeiten / Deaktivieren / Wiederherstellen / Terminieren / Probelauf / Exportieren stehen der Studienleitung und der Administration zur Verfügung.',
        'Regeln können Diskrepanznotizen automatisch erstellen — diese erscheinen dann in **Rückfragen & Diskrepanzen** zur Nachverfolgung.',
      ],
    },
    {
      id: 'dm-groups', num: '7', title: 'Teilnehmer-Gruppen', deutsch: 'Group Classes', route: '/group-classes',
      roles: ['administrator','data-manager'],
      shot: 'data-manager/06-group-classes.png',
      goal: 'Teilnehmende für die Auswertung gruppieren (Arme, Kohorten, Familien).',
      steps: [
        'Öffnen Sie **Teilnehmer-Gruppen** (Group Classes). Jede Zeile ist eine Gruppenklasse mit ihren untergeordneten Gruppen als Chips dargestellt.',
        'Klicken Sie auf **Anlegen**, um eine hinzuzufügen: Geben Sie einen **Namen** ein, wählen Sie einen **Typ** (*Arm* / *Familie* / *Demografie* / *Sonstige*) und eine **Teilnehmer-Zuordnung** (*erforderlich* / *optional*).',
        'Fügen Sie untergeordnete **Gruppen** in den Zeilen darunter hinzu; weitere fügen Sie mit **Gruppe hinzufügen** hinzu und leere Zeilen entfernen Sie mit **×**.',
        '**Deaktivieren** entfernt eine Gruppenklasse; **Wiederherstellen** bringt sie zurück.',
      ],
      notes: [
        'Wenn eine **erforderliche** Gruppenklasse existiert, muss die Aufnahme jeden Teilnehmenden einer Gruppe zuordnen, und die Studienteilnehmer-Matrix lässt sich nach Gruppe lesen.',
      ],
    },
    {
      id: 'dm-notes', num: '8', title: 'Rückfragen & Diskrepanzen', deutsch: 'Notes & Discrepancies', route: '/notes',
      roles: ['data-manager','monitor','administrator'],
      shot: 'data-manager/07-notes-discrepancies.png',
      goal: 'Datenrückfragen über die gesamte Studie hinweg beaufsichtigen und auflösen.',
      steps: [
        'Öffnen Sie **Rückfragen & Diskrepanzen** (Notes & Discrepancies). Übersichtskarten zählen offene Notizen nach Typ: **Rückfrage** (query), **fehlgeschlagene Validierung** (failed validation), **Anmerkung** (annotation) und **Änderungsgrund** (reason for change).',
        'Filtern Sie nach Status (Standard **nur offen**), nach Typ, nach Freitext oder setzen Sie das Häkchen bei **mir zugewiesen**.',
        'Klappen Sie eine Zeile (Pfeil) auf, um den vollständigen Verlauf zu lesen; der **Item**-Link verlinkt direkt auf die CRF-Zeile, und die Zeile zeigt den aktuellen Wert im Kontext.',
        'Bearbeiten Sie eine Notiz mit **Antworten**, **Als gelöst markieren** oder **Schließen** — jede öffnet einen Inline-Editor für den erforderlichen Kommentar (Schließen darf wortlos sein).',
        '**CSV exportieren** schreibt die aktuell gefilterte Liste zur Offline-Durchsicht.',
      ],
      notes: [
        'Welche Aktionen Sie sehen, hängt vom Status der Notiz und Ihrer Rolle ab; als Studienleitung haben Sie zur Aufsicht eine umfassende Schließ-/Lösungsbefugnis.',
        'Jede Antwort und jede Statusänderung wird im Audit Trail festgehalten.',
      ],
    },
    {
      id: 'dm-audit', num: '9', title: 'Audit Trail der Studie', deutsch: 'Study Audit Log', route: '/audit-log',
      roles: ['data-manager','monitor','administrator'], tall: true,
      shot: 'data-manager/08-study-audit-log.png',
      goal: 'Nachvollziehen, wer was, wann und warum geändert hat.',
      steps: [
        'Öffnen Sie **Audit Trail der Studie** (Study Audit Log). Ereignisse sind auf einer Zeitleiste nach Datum gruppiert (Heute / Gestern / TT.MM.JJJJ).',
        'Filtern Sie nach **Akteur/-in**, nach **Typ** (signiert, Änderungsgrund, SDV, Administration, Daten, Rückfrage, Teilnehmer-Gruppenwechsel) oder nach **Teilnehmer**.',
        'Klappen Sie ein Ereignis auf, um die **Vorher/Nachher**-Differenz und einen etwaigen Änderungsgrund zu sehen.',
        '**XLSX exportieren** schreibt die gefilterte Ansicht für eine Compliance-Akte.',
      ],
      notes: [
        'Die Rollen-Chips der Akteure sind farbcodiert (Prüfärztin/-arzt / Monitor / Studienleitung), damit Sie auf einen Blick erkennen, wer gehandelt hat.',
        'Der Audit Trail ist schreibgeschützt — er ist das maßgebliche System (system of record), keine Bearbeitungsoberfläche.',
      ],
    },
    {
      id: 'dm-datasets', num: '10', title: 'Datenexport', deutsch: 'Datasets / Data Export', route: '/datasets',
      roles: ['data-manager','monitor','administrator'],
      shot: 'data-manager/09-datasets.png',
      goal: 'Die Daten der Studie in dem Format extrahieren, das Ihre Auswertung benötigt.',
      steps: [
        'Öffnen Sie **Datenexport** (Datasets / Data Export). Die Tabelle listet gespeicherte Datasets mit Eigentümer/-in, Erstellungsdatum, letztem Lauf und Dateianzahl.',
        'Für einen schnellen Vollexport nutzen Sie **Schnell-ODM** (Quick ODM) im Kopfbereich.',
        'Für ein gespeichertes Dataset öffnet **Jetzt exportieren** eine Formatauswahl — **ODM**, **CSV**, **TSV**, **Excel**, **SAS** oder **SPSS** — und lädt das Ergebnis herunter.',
        '**Dateien anzeigen** klappt eine Unterzeile auf, die jede erzeugte Datei mit Größe, Zeitstempel und einem **Herunterladen**-Link auflistet.',
        '**Entfernen** löscht ein Dataset weich (soft-delete); setzen Sie das Häkchen bei **entfernte anzeigen**, um es wieder**herzustellen**.',
      ],
      notes: [
        'Das Bearbeiten eines Datasets ist gesperrt, sobald es ausgeführt wurde (um einen reproduzierbaren Extrakt zu wahren); legen Sie stattdessen ein neues an.',
      ],
    },
    {
      id: 'dm-create-dataset', num: '11', title: 'Neues Dataset', deutsch: 'Create Dataset (wizard)', route: '/datasets/new',
      roles: ['data-manager','administrator'],
      shot: 'data-manager/10-create-dataset.png',
      goal: 'Genau festlegen, welche Visiten, CRFs und Items ein Export enthält.',
      steps: [
        'Klicken Sie in **Datenexport** auf **Neues Dataset**, um den Anlege-Assistenten unter `/datasets/new` zu öffnen.',
        'Gehen Sie den Assistenten durch, um das Dataset zu benennen und seinen Geltungsbereich zu wählen — die einzuschließenden Visiten, CRFs und Items sowie etwaige Datums-/Statusfilter.',
        'Speichern Sie das Dataset; es erscheint dann in der Dataset-Tabelle, wo Sie es in dem von Ihnen benötigten Format ausführen.',
      ],
      notes: [
        'Ein gespeichertes Dataset ist wiederverwendbar — definieren Sie den Geltungsbereich einmal und exportieren Sie ihn wiederholt, während die Studie Daten ansammelt. Eine Bearbeitung ist nur vor dem ersten Lauf des Datasets möglich.',
      ],
    },
    {
      id: 'dm-import', num: '12', title: 'CRF-Daten importieren', deutsch: 'Import CRF Data', route: '/import-crf-data',
      roles: ['administrator','data-manager'],
      shot: 'data-manager/11-import-crf-data.png',
      goal: 'Daten aus einer CDISC-ODM-XML-Datei in die Studie laden.',
      steps: [
        'Öffnen Sie **CRF-Daten importieren** (Import CRF Data). Der Assistent hat vier Schritte: **Hochladen → Zuordnen → Vorschau → Festschreiben**.',
        '**Laden** Sie die ODM-`.xml`-Datei hoch (per Drag-and-drop oder Auswahl).',
        '**Zuordnen** zeigt die erkannten Zählungen — Teilnehmende, Visiten, CRFs und Zeilen.',
        '**Vorschau** klassifiziert jede Zeile als **bereit**, **überschreiben**, **Warnung** oder **Fehler**, mit einer Vorher/Nachher-Differenz. Wählen Sie **ersetzen** oder **überspringen**; das Ersetzen erfordert einen **Änderungsgrund**.',
        'Sie können nicht festschreiben, solange eine Zeile im Zustand **Fehler** ist. **Festschreiben** schreibt den Import und meldet eingefügte / überschriebene / übersprungene Zeilen sowie erstellte Diskrepanznotizen.',
      ],
      notes: [
        'Das Überschreiben von Werten per Import wird wie jede andere Änderung auditiert — der von Ihnen eingegebene Grund wird mit jedem Überschreiben gespeichert. Das Upload-Token kann ablaufen; falls dies geschieht, laden Sie erneut hoch und beginnen von vorn.',
      ],
    },
    {
      id: 'dm-dde', num: '13', title: 'DDE-Abgleich', deutsch: 'Double-Data-Entry (DDE) Reconciliation', route: '/event-crfs/:oid/dde-reconcile',
      roles: ['data-manager','administrator','investigator'],
      goal: 'Ein zweifach erfasstes CRF abgleichen und je Item den maßgeblichen Wert auswählen.',
      steps: [
        'Der DDE-Abgleich öffnet sich für ein bestimmtes Event-CRF unter `/event-crfs/:eventCrfOid/dde-reconcile`.',
        'Der Bildschirm listet jedes widersprüchliche Item nebeneinander auf — die **Erst**eingabe (initial) gegenüber der **Zweit**eingabe (double).',
        'Wählen Sie für jeden Konflikt den **maßgeblichen** Wert (oder geben Sie einen manuellen Wert ein) und tragen Sie einen **Änderungsgrund** ein.',
        '**Übernehmen** Sie die Auflösung je Zeile; die laufende **Anzahl offener** Konflikte sinkt, und eine Bestätigung erscheint, sobald das CRF vollständig abgeglichen ist.',
      ],
      notes: [
        'Erreichbar für **Studienleitung**, **Administration** und **Prüfärztin/-arzt**; das Backend ist die maßgebliche Zugangskontrolle.',
        'Jede Auflösung wird mit ihrem Grund im Audit Trail festgehalten — der DDE-Abgleich ist eine Datenqualitätskontrolle, behandeln Sie das Grund-Feld daher als Teil des Datensatzes.',
      ],
    },
  ],
}
