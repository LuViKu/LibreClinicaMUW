import type { ManualChapter } from '../manualTypes'

export const administratorDe: ManualChapter = {
  id: 'administrator', role: 'administrator', kicker: 'Kapitel 01',
  title: 'Administrator/-in', deutsch: 'Administrator',
  oneLiner: 'System- und Studienaufbau: Nutzer, Standorte, Studien, CRFs, Regeln, Konfiguration, Audit. Eine Obermenge jeder anderen Rolle.',
  intro: [
    'Die Rolle **Administrator/-in** ist die übergeordnete Rolle (Obermenge) in LibreClinicaMUW. Sie vereint den **Systemaufbau** (Nutzerkonten, Standorte, Modalitätenkatalog, Passwort-Richtlinie, geplante Jobs, Systemstatus) und den **Studienaufbau** (Anlegen von Studien, Erstellen von Visiten-Definitionen und CRFs, Regeln, Datensätze, Datenimport). Überall dort, wo die Anwendung eine Ansicht auf *Administrator/-in* beschränkt, erhalten Sie Zugriff; mehrere dieser Ansichten sind für keine andere Rolle sichtbar.',
  ],
  callout: {
    kind: 'info', title: 'Rollensteuerung',
    text: 'Die Web-Anwendung blendet Bedienelemente aus, die Sie nicht verwenden dürfen, doch der *Server* ist die maßgebliche Instanz — jede ausschließlich Administrator/-innen vorbehaltene Aktion wird im Backend erneut geprüft, und jede Lebenszyklus-Aktion (Deaktivieren, Wiederherstellen, Zurücksetzen, Statuswechsel) wird in den Audit-Trail geschrieben. Die Rolle Administrator/-in erbt **nicht** stillschweigend andere Rollen: Studienbezogene Ansichten öffnen sich, weil sie *Administrator/-in* als zulässige Rolle aufführen, nicht durch Vererbung.',
  },
  sections: [
    {
      id: 'ad-matrix', num: '1', title: 'Studienteilnehmer', deutsch: 'Subject Matrix', route: '/subjects',
      roles: ['investigator','monitor','data-manager','administrator'],
      shot: 'administrator/01-subject-matrix.png',
      goal: 'Sehen Sie auf einen Blick alle Teilnehmenden der aktiven Studie und deren Fortschritt je Visite.',
      steps: [
        'Öffnen Sie **Studienteilnehmer** (Subject Matrix) über die Startseite oder die Seitenleiste (`/subjects`).',
        'Verwenden Sie das Suchfeld und die Filter-Chips (*all*, *today*, *ready-to-sign*, *open-events*, *all-events-complete*, *signed*), um die Liste einzugrenzen; aktivieren Sie **Nur mit Queries**, um nur Teilnehmende mit offenen Rückfragen anzuzeigen.',
        'Lesen Sie jede Zeile: die fixierte linke Spalte ist die **Subject ID**; die scrollbaren Spalten zeigen eine Status-Plakette je Visite sowie Geschlecht, Studienauge (OD/OS/OU), Gruppe und Einschlussdatum.',
        'Verwenden Sie die Visiten-Steuerung **V1 … VN** (Erste / Vorherige / Nächste / Letzte) oberhalb der Tabelle, um durch die Visiten zu blättern.',
        'Klicken Sie rechts in einer Zeile auf **Öffnen** (Open), um die Detailseite der/des Teilnehmenden zu öffnen.',
      ],
      notes: [
        'Zugänglich für Prüfärztin/-arzt, Monitor, Studienleitung und Administrator/-in. Die Schaltfläche **Export** lädt die Matrix herunter; **Studienteilnehmer hinzufügen** (Add Subject) startet den Einschluss.',
      ],
    },
    {
      id: 'ad-users', num: '2', title: 'Nutzerverwaltung', deutsch: 'Manage Users', route: '/manage-users',
      roles: ['administrator'],
      shot: 'administrator/02-manage-users.png',
      goal: 'Konten anlegen, bearbeiten, deaktivieren/wiederherstellen, entsperren und Passwörter zurücksetzen sowie studienbezogene Rollen zuweisen.',
      steps: [
        'Öffnen Sie **Nutzerverwaltung** (Manage Users) über die Startseite oder die Seitenleiste (`/manage-users`).',
        'Um eine Person hinzuzufügen, klicken Sie auf **Nutzer/-in einladen** (Invite User) und füllen Sie den Einladungsdialog aus; bei Erfolg wird ein Einmalpasswort inline angezeigt — kopieren Sie es und übergeben Sie es über einen sicheren Kanal.',
        'Filtern Sie die Tabelle über das Suchfeld, die Auswahlmenüs **Rolle** und **Auth** sowie das Kontrollkästchen **Nur aktive** (only active).',
        'Nutzen Sie in jeder Zeile die Inline-Aktionen: **Bearbeiten** (Edit), **Deaktivieren / Reaktivieren** (Disable / Restore), **Passwort zurücksetzen** (Reset Password) und **Entsperren** (Unlock) — nur für aktive *Lokal*-Konten angeboten — sowie **Rollen** (Roles).',
        'Nach einer Wiederherstellung, einem Zurücksetzen oder einem Entsperren erscheint das erzeugte Einmalpasswort im grünen Bereich unterhalb der Tabelle — kopieren Sie es, bevor Sie ihn schließen.',
      ],
      notes: [
        'Diese Ansicht ist **ausschließlich Administrator/-innen vorbehalten** — die Einladungsschaltfläche und jede zeilenbezogene Aktion sind für andere Rollen ausgeblendet.',
        'Die Rollen werden mit deutschen Bezeichnungen angezeigt: *Prüfarzt/-ärztin* (Investigator), *Monitor*, *Studienleitung* (Data Manager), *Administrator/-in*, *Koordinator/-in* (CRC). Die Spalte **Auth** unterscheidet *Lokal*, *SSO*, *LDAP (Legacy)* und *Einladung offen*; Zurücksetzen und Entsperren stehen für SSO-/LDAP-Konten nicht zur Verfügung, da der Identitätsanbieter die Zugangsdaten verwaltet.',
        'Jedes Deaktivieren, Wiederherstellen, Zurücksetzen und Entsperren wird im Audit-Trail dokumentiert; über den Mehrrollen-Dialog kann eine Person mehrere Rollen pro Studie innehaben.',
      ],
    },
    {
      id: 'ad-sites', num: '3', title: 'Standorte', deutsch: 'Sites', route: '/sites',
      roles: ['administrator','data-manager'],
      shot: 'administrator/03-sites.png',
      goal: 'Legen Sie die Standorte (Zentren) unter der aktiven Studie an und verwalten Sie sie.',
      steps: [
        'Öffnen Sie **Standorte** (Sites) über die Startseite (`/sites`).',
        'Klicken Sie auf **Neuer Standort** (New Site), um das Inline-Formular zum Anlegen zu öffnen.',
        'Füllen Sie die Pflichtfelder aus — *Name*, *Unique Protocol ID*, *Principal Investigator* — sowie etwaige optionale Angaben, und klicken Sie dann auf Anlegen.',
        'Verwenden Sie bei einem bestehenden Standort **Entfernen** (Disable) zum sanften Entfernen oder **Wiederherstellen** (Restore), um ihn zurückzuholen.',
      ],
      notes: [
        'Die aktive Studie muss die übergeordnete Studie der obersten Ebene sein — die Ansicht ist ausgeblendet, wenn die aktive Studie selbst ein Standort ist. Die Verwaltung von Standorten ist für Administrator/-in und Studienleitung zugänglich.',
        'Das Deaktivieren eines Standorts bewahrt seine eingeschlossenen Teilnehmenden und Visiten im Audit-Trail, macht sie aber unzugänglich, bis der Standort wiederhergestellt wird; der Bestätigungsdialog weist darauf hin.',
      ],
    },
    {
      id: 'ad-build', num: '4', title: 'Studienaufbau', deutsch: 'Build Study', route: '/build-study',
      roles: ['administrator','data-manager'],
      shot: 'administrator/04-build-study.png',
      goal: 'Verfolgen Sie die Checkliste zum Studienaufbau und führen Sie jede Aufbauaufgabe zum Abschluss.',
      steps: [
        'Öffnen Sie **Studienaufbau** (Build Study) über die Startseite oder die Seitenleiste (`/build-study`).',
        'Lesen Sie die Fortschrittskarte (abgeschlossene Aufgaben, Prozentsatz, Anzahl der Standorte und der eingeschlossenen Teilnehmenden).',
        'Arbeiten Sie die Aufgabenkacheln ab — *Create Study*, *CRF Library*, *Event Definitions*, *Sites*, *Group Classes*, *Rules*, *Manage Users* — jeweils mit einer Status-Plakette. Klicken Sie auf **→ Weiter**, um zu der jeweiligen Aufgabe zu springen.',
        'Klicken Sie bei optionalen Aufgaben mit Nullzähler auf **Als abgeschlossen markieren** (Mark as complete), um diese zu bestätigen.',
        'Verwenden Sie als Administrator/-in die Symbolleiste, um den Studienstatus zu ändern: Wählen Sie einen Zielstatus und bestätigen Sie im Dialog **Status wechseln zu …**, wobei Sie beim Wechsel zu LOCKED oder FROZEN einen Grund angeben.',
      ],
      notes: [
        'Studienaufbau ist für Administrator/-in und Studienleitung zugänglich, doch das Status-Auswahlmenü, die Aktion zum Statuswechsel und die Schaltfläche *Neue Studie* sind ausschließlich Administrator/-innen vorbehalten. Bei den Übergängen zu LOCKED und FROZEN ist ein Grund verpflichtend und wird im Audit-Trail festgehalten.',
      ],
    },
    {
      id: 'ad-events', num: '5', title: 'Visiten-Definitionen', deutsch: 'Event Definitions', route: '/event-definitions',
      roles: ['administrator','data-manager'],
      shot: 'administrator/05-event-definitions.png',
      goal: 'Definieren Sie die Visiten (Events), aus denen sich der Studienplan zusammensetzt.',
      steps: [
        'Öffnen Sie aus **Studienaufbau** die Aufgabe **Event Definitions** oder rufen Sie `/event-definitions` auf.',
        'Prüfen Sie die Liste der **Visiten-Definitionen** (Event Definitions).',
        'Fügen Sie eine Definition hinzu oder bearbeiten Sie sie, indem Sie Name, Typ (z. B. geplant vs. wiederholend) und die zugeordneten CRFs festlegen.',
        'Ordnen Sie die Definitionen so an, dass sie der klinischen Visitenabfolge entsprechen.',
      ],
      notes: [
        'Zugänglich für Administrator/-in und Studienleitung. Visiten-Definitionen bilden das Rückgrat der Spalten in der Subject Matrix, sodass Änderungen hier sich in der Visitenliste jeder/jedes Teilnehmenden niederschlagen.',
      ],
    },
    {
      id: 'ad-crf', num: '6', title: 'CRF-Bibliothek', deutsch: 'CRF Library (and CRF Builder)', route: '/crf-library',
      roles: ['administrator','data-manager'],
      shot: 'administrator/06-crf-library.png',
      goal: 'Erstellen Sie Case Report Forms, verfassen Sie neue Versionen und verwalten Sie den Versions-Lebenszyklus.',
      steps: [
        'Öffnen Sie **CRF-Bibliothek** (CRF Library) über Studienaufbau oder `/crf-library`.',
        'Aktivieren Sie **Entfernte einschließen** (Include removed), um sanft gelöschte CRFs anzuzeigen.',
        'Klicken Sie auf **Neue CRF** (New CRF) und geben Sie einen Namen sowie eine optionale Beschreibung ein, um die Formularhülle anzulegen.',
        'Klicken Sie auf einer CRF-Karte auf **Neue Version anlegen**, um die Drag-and-drop-Arbeitsfläche **CRF Builder** zu öffnen (`/crf-authoring-canvas/<oid>`), auf der Sie Abschnitte und Items platzieren, Datentypen und Bezeichnungen festlegen und anschließend die Version speichern.',
        'Verwenden Sie die Versions-Unterliste, um **XLS herunterladen** sowie Sperren / Entsperren / Wiederherstellen / **Entfernen** auszuführen. *Hard Remove* ist ausschließlich Systemadministrator/-innen vorbehalten und gesperrt, solange noch eine Visiten-Definition oder eine Event-CRF auf die Version verweist.',
      ],
      notes: [
        'Zugänglich für Administrator/-in und Studienleitung. Die Drag-and-drop-Arbeitsfläche ist die einzige Oberfläche zum Erstellen von CRFs; der frühere Assistent in der Seitenleiste wurde entfernt.',
        'Eine in Verwendung befindliche Version kann nicht endgültig entfernt werden — der Blocker-Dialog führt die Visiten-Definitionen und Beispiel-Teilnehmenden auf, die sie halten.',
      ],
    },
    {
      id: 'ad-rules', num: '7', title: 'Regeln', deutsch: 'Rules', route: '/rules',
      roles: ['administrator','data-manager'],
      shot: 'administrator/07-rules.png',
      goal: 'Prüfen, verfassen, testen und planen Sie die Edit-Check- und Automatisierungsregeln der Studie.',
      steps: [
        'Öffnen Sie **Regeln** (Rules) über Studienaufbau oder `/rules`.',
        'Suchen und wählen Sie im linken Bereich ein Regelset; dessen Ziel, zugeordnete Regeln und Ausführungsplan erscheinen rechts.',
        'Klicken Sie auf **Neue Regel**, um den Regel-Assistenten zu öffnen, oder auf **Regeln importieren**, um Regeln aus XML zu laden.',
        'Bearbeiten Sie für ein ausgewähltes Regelset den Ausführungsplan, bearbeiten Sie einzelne Aktionsmeldungen und führen Sie **Trockentest ausführen** (Dry run) aus, um vorab zu sehen, bei welchen Teilnehmenden die Regeln auslösen würden.',
        'Verwenden Sie **XML exportieren**, um alle Regeln herunterzuladen, und die **Test**-Sandbox unten, um einen Ausdruck gegen Beispielwerte auszuwerten.',
      ],
      notes: [
        'Zugänglich für Administrator/-in und Studienleitung; für andere Rollen ist die Ansicht schreibgeschützt. Regelaktionen umfassen Diskrepanz-Notizen, E-Mail, Benachrichtigungen, Ein-/Ausblenden, Einfügen und Randomisieren. Das Ausführungsprotokoll erfasst jede ausgelöste Aktion mit Zeitstempel.',
      ],
    },
    {
      id: 'ad-groups', num: '8', title: 'Teilnehmer-Gruppen', deutsch: 'Group Classes', route: '/group-classes',
      roles: ['administrator','data-manager'],
      shot: 'administrator/08-group-classes.png',
      goal: 'Definieren Sie Teilnehmer-Gruppierungen (Arme, Kohorten, Familien) für die Studie.',
      steps: [
        'Öffnen Sie **Teilnehmer-Gruppen** (Group Classes) über Studienaufbau oder `/group-classes`.',
        'Fügen Sie eine Gruppenklasse hinzu, benennen Sie sie und wählen Sie ihren Typ.',
        'Definieren Sie die Gruppenwerte, denen Teilnehmende zugewiesen werden können.',
      ],
      notes: [
        'Zugänglich für Administrator/-in und Studienleitung. Die Gruppenzuordnung erscheint anschließend auf der Detailseite jeder/jedes Teilnehmenden sowie als Spalte in der Subject Matrix. Dies ist eine optionale Aufbauaufgabe — sie kann auch ohne jede Gruppe als abgeschlossen bestätigt werden.',
      ],
    },
    {
      id: 'ad-modalities', num: '9', title: 'Modalitäten', deutsch: 'Modalitäten', route: '/modalities',
      roles: ['administrator'],
      shot: 'administrator/09-modalities.png',
      goal: 'Pflegen Sie den plattformweiten Katalog der Messmodalitäten und ihrer augenbezogenen Item-Bindungen.',
      steps: [
        'Öffnen Sie **Modalitäten** (Modalities) über die Startseite (`/modalities`).',
        'Klicken Sie auf **Neue Modalität**, um eine zu registrieren. Geben Sie den stabilen **code** an (nach dem Anlegen fixiert), deutsche und englische Bezeichnungen, die Ordnungszahl, den Datentyp (numerisch / kategorial), eine optionale Einheit (mm, mmHg, …) sowie die OD- und/oder OS-Item-OID(s) — mindestens eine ist erforderlich.',
        'Verwenden Sie **Bearbeiten**, um eine Modalität zu ändern, oder **Entfernen**, um sie außer Betrieb zu nehmen.',
      ],
      notes: [
        'Dies ist ein **ausschließlich Administrator/-innen vorbehaltener**, plattformweiter Katalog (nicht studienbezogen). Die Spalten sind *Code*, deutsche/englische Bezeichnungen, OD- und OS-Item-OIDs, Typ, Einheit und Ordnungszahl.',
        'Das Entfernen einer Modalität bewahrt bestehende Baseline-Messungen, entfernt die Modalität aber aus dem augenbezogenen Baseline-Panel; der Bestätigungsdialog weist darauf hin.',
      ],
    },
    {
      id: 'ad-datasets', num: '10', title: 'Datenexport', deutsch: 'Datasets / Data Export', route: '/datasets',
      roles: ['administrator','data-manager','monitor'],
      shot: 'administrator/10-datasets.png',
      goal: 'Erstellen Sie wiederverwendbare Export-Datensätze und laden Sie Studiendaten im gewünschten Format herunter.',
      steps: [
        'Öffnen Sie **Datenexport** (Data Export) über die Startseite oder die Seitenleiste (`/datasets`).',
        'Klicken Sie für einen schnellen Auszug auf **Schnell-ODM-Export**. Klicken Sie für einen maßgeschneiderten Auszug auf **Neuer Datensatz** und füllen Sie den Datensatz-Assistenten aus.',
        'Erweitern Sie in der Datensatz-Tabelle **View files**, **Open wizard**, um einen Datensatz zu bearbeiten (deaktiviert, sobald er ausgeführt wurde), oder **Remove** / **Restore**, um seinen Lebenszyklus zu verwalten.',
        'Klicken Sie auf **Export now** und wählen Sie ein Format — *odm*, *csv*, *tsv*, *excel*, *sas* oder *spss* — und laden Sie dann die erzeugte Datei herunter.',
      ],
      notes: [
        'Zugänglich für Administrator/-in, Studienleitung und Monitor. Aktivieren Sie **Entfernte einschließen**, um sanft gelöschte Datensätze anzuzeigen. Ein bereits ausgeführter Datensatz kann nicht bearbeitet werden — klonen Sie ihn oder legen Sie stattdessen einen neuen an.',
      ],
    },
    {
      id: 'ad-import', num: '11', title: 'CRF-Daten importieren', deutsch: 'Import CRF Data', route: '/import-crf-data',
      roles: ['administrator','data-manager'],
      shot: 'administrator/11-import-crf-data.png',
      goal: 'Laden Sie CRF-Daten aus einer ODM-XML-Datei in einem geführten, vierstufigen Assistenten gesammelt ein.',
      steps: [
        'Öffnen Sie **CRF-Daten importieren** über die Startseite oder die Seitenleiste (`/import-crf-data`).',
        '**Hochladen** (Upload): Ziehen Sie eine `.xml`-Datei in die Ablagezone (oder durchsuchen Sie das Dateisystem) und klicken Sie dann auf **Weiter**.',
        '**Mappen** (Map): Prüfen Sie die erkannten Anzahlen von Teilnehmenden, Visiten, CRFs und Zeilen und fahren Sie dann fort.',
        '**Vorschau & Auflösung** (Preview & Resolve): Prüfen Sie die Statuskarten (*Ready*, *Overwrite*, *Warning*, *Error*) und die zeilenbezogene Tabelle. Lösen Sie Probleme, wählen Sie einen Überschreibmodus und — falls Zeilen ersetzt werden — geben Sie einen **Reason for change** ein, bevor Sie fortfahren.',
        '**Commit**: Das System wendet den Import an und zeigt eine Zusammenfassung (eingefügte, überschriebene, übersprungene Zeilen, Diskrepanz-Notizen). Verwenden Sie **Start over**, um eine weitere Datei zu importieren.',
      ],
      notes: [
        'Zugänglich für Administrator/-in und Studienleitung. Der Commit-Schritt wird vom System ausgelöst, nicht angeklickt. Sie können nicht committen, solange eine Zeile im Fehlerzustand ist; das Überschreiben im *replace*-Modus erfordert einen Grund, der im Audit-Trail festgehalten wird. Läuft das Upload-Token vor dem Commit ab, laden Sie die Datei erneut hoch.',
      ],
    },
    {
      id: 'ad-sysaudit', num: '12', title: 'System-Audit-Protokoll', deutsch: 'System Audit Log', route: '/system/audit-log',
      roles: ['administrator'], tall: true,
      shot: 'administrator/12-system-audit-log.png',
      goal: 'Prüfen Sie den institutionsweiten Audit-Trail, einschließlich fehlgeschlagener Operationen und Jobs.',
      steps: [
        'Rufen Sie **System-Audit-Protokoll** unter `/system/audit-log` auf.',
        'Filtern Sie nach Akteur/-in, Ereignisvariante (signed, reason-for-change, sdv, admin, data, query, subject-group-change) oder Teilnehmer/-in.',
        'Durchsuchen Sie die nach Datum gruppierte Zeitleiste; klicken Sie auf einen Eintrag mit Chevron, um seinen Vorher-Nachher-Vergleich und den Grund aufzuklappen.',
      ],
      notes: [
        'Dieses systemweite Protokoll ist **ausschließlich Administrator/-innen vorbehalten** und bringt Zeilen zum Vorschein, die der studienbezogene *Audit Trail der Studie* ausblendet — insbesondere `OPERATION_FAILED`- und `JOB_FAILED`-Einträge. Es hat keinen Studienbezug und (anders als die studienbezogene Audit-Ansicht) keinen XLSX-Export.',
      ],
    },
    {
      id: 'ad-status', num: '13', title: 'Systemstatus', deutsch: 'System Status', route: '/admin/system-status',
      roles: ['administrator'],
      shot: 'administrator/13-system-status.png',
      goal: 'Prüfen Sie den Zustand der laufenden Anwendung nach einem Neustart oder Zwischenfall.',
      steps: [
        'Rufen Sie **Systemstatus** unter `/admin/system-status` auf.',
        'Lesen Sie die drei Panels: **JVM** (Java-Version, Heap, Threads, CPUs), **Database** (Erreichbarkeit, Produkt/Version, Anzahl der Liquibase-Changelogs) und **Application** (Status OK/OutOfMemory, Laufzeit).',
        'Klicken Sie auf **Aktualisieren** (Refresh), um erneut abzufragen; die Zeit der letzten Aktualisierung wird neben der Schaltfläche angezeigt.',
      ],
      notes: ['Ausschließlich Administrator/-innen vorbehalten; das Backend gibt für jede andere Sitzung 403 zurück. Dies ist eine schreibgeschützte Diagnoseansicht.'],
    },
    {
      id: 'ad-password', num: '14', title: 'Passwort-Richtlinie', deutsch: 'Password Policy', route: '/admin/password-policy',
      roles: ['administrator'],
      shot: 'administrator/14-password-policy.png',
      goal: 'Legen Sie die für lokale Konten durchgesetzten Passwortregeln fest.',
      steps: [
        'Rufen Sie **Passwort-Richtlinie** unter `/admin/password-policy` auf.',
        'Aktivieren Sie die erforderlichen Zeichenklassen (Kleinbuchstaben, Großbuchstaben, Ziffern, Sonderzeichen).',
        'Legen Sie die Längenbeschränkungen fest — *Min Length*, *Max Length* (1–256) — und *Expiration Days* (0 = kein Ablauf).',
        'Verlangen Sie optional eine Passwortänderung bei der Erstanmeldung und klicken Sie dann auf **Save** (oder **Discard**).',
      ],
      notes: [
        'Ausschließlich Administrator/-innen vorbehalten. Bei Erfolg erscheint ein Banner *Gespeichert* (Saved). Die Richtlinie gilt für lokale Konten; SSO-/LDAP-Zugangsdaten werden vom Identitätsanbieter verwaltet.',
      ],
    },
    {
      id: 'ad-config', num: '15', title: 'Anwendungskonfiguration', deutsch: 'App Configuration', route: '/admin/config',
      roles: ['administrator'],
      shot: 'administrator/15-app-config.png',
      goal: 'Sehen Sie, welche Konfiguration die laufende Anwendung tatsächlich liest.',
      steps: [
        'Rufen Sie **Anwendungskonfiguration** unter `/admin/config` auf.',
        'Lesen Sie die Werte: Standard-Zeitzone, Sprache/Land der Nutzenden, Dateikodierung, Betriebssystemname/-architektur, JVM-Optionen, die Remote-Push-URL für die retinale Inferenz sowie ob SSO aktiviert ist.',
        'Klicken Sie auf **Aktualisieren**, um nach einem Neustart erneut einzulesen.',
      ],
      notes: [
        'Ausschließlich Administrator/-innen vorbehalten und **schreibgeschützt** — die Deployment-Konfiguration liegt in Umgebungsvariablen, nicht hier. Diese Ansicht bringt zum Vorschein, was die laufende JVM sieht, und ist für Diagnosen nach einem Neustart nützlich.',
      ],
    },
    {
      id: 'ad-jobs', num: '16', title: 'Geplante Jobs', deutsch: 'Scheduled Jobs', route: '/admin/jobs',
      roles: ['administrator'],
      shot: 'administrator/16-scheduled-jobs.png',
      goal: 'Prüfen Sie die Hintergrund-Jobs (Quartz), die die Plattform ausführt.',
      steps: [
        'Rufen Sie **Geplante Jobs** unter `/admin/jobs` auf.',
        'Prüfen Sie die Statusleiste des Schedulers (Name, gestartet/Bereitschaft) und die Jobtabelle: Name, Gruppe, Status, vorherige und nächste Auslösezeiten, Beschreibung.',
        'Klicken Sie auf **Aktualisieren**, um erneut abzufragen.',
      ],
      notes: ['Ausschließlich Administrator/-innen vorbehalten und schreibgeschützt. Die Status-Plakette ist farblich codiert (NORMAL grün, PAUSED gelb, ERROR/BLOCKED rot). Eine leere Liste zeigt *Keine Jobs*.'],
    },
    {
      id: 'ad-subject', num: '17', title: 'Probandendetail · Studien-Identität', deutsch: 'Subject Detail, Study Identity & Parameters', route: '/subjects/<id>',
      roles: ['investigator','administrator'],
      shot: 'administrator/20-subject-detail.png',
      goal: 'Prüfen Sie eine einzelne teilnehmende Person und bearbeiten Sie Identität und Parameter der aktiven Studie.',
      steps: [
        'Klicken Sie in der Subject Matrix in einer Zeile auf **Öffnen**, um die Detailseite der teilnehmenden Person zu öffnen (`/subjects/<id>`).',
        'Prüfen Sie den Block **IDENTITÄT** und die Tabelle **BESUCHE** (Visits). Verwenden Sie **Bearbeiten**, um Identitätsfelder anzupassen, **CRFs öffnen**, um Daten einzugeben, und das Zeilen-Kebab-Menü (⋮) für *Stornieren* (Cancel) oder *Signieren* (Sign).',
        'Um eine Studie anzulegen, öffnen Sie **Neue Studie** über Studienaufbau oder rufen Sie `/studies/new` auf.',
        'Um die Identität zu ändern, öffnen Sie **Bearbeiten** (`/studies/<oid>/edit`) — Name, sekundäre Protokoll-ID, Phase, Zusammenfassung, Principal Investigator, Sponsor, offizieller Titel und Protokolltyp. Klicken Sie auf **Save**.',
        'Um das Verhalten zu ändern, öffnen Sie **Parameter** (`/studies/<oid>/parameters`) — Erzeugung der Subject-ID, Diskrepanzmanagement, Interviewer-Voreinstellungen und Modul-Schalter. Klicken Sie auf **Save**.',
      ],
      notes: [
        'Das Probandendetail ist für Prüfärztin/-arzt und Administrator/-in zugänglich. Anlegen einer Studie, Bearbeiten der Identität und Bearbeiten der Parameter sind **ausschließlich Administrator/-innen vorbehalten** und werden im Backend erneut geprüft (403 bei Verweigerung).',
        'Bearbeitungen der Probanden-Identität, Augen-Übergänge, Visiten-Stornierungen und Signaturen werden allesamt im Audit-Trail festgehalten.',
      ],
    },
    {
      id: 'ad-parked', num: '18', title: 'Geparkte Scans', deutsch: 'Parked Scans (cross-study retinal jobs)', route: '/retinal/parked',
      roles: ['administrator'],
      shot: 'administrator/22-parked-scans.png',
      goal: 'Prüfen Sie retinale Inferenz-Jobs, die ohne Visite hochgeladen wurden und auf die Zuordnung zu einer teilnehmenden Person warten.',
      steps: [
        'Öffnen Sie **Geparkte Scans** (`/retinal/parked`) — eine ausschließlich Systemadministrator/-innen vorbehaltene, studienübergreifende Übersicht.',
        'Jede Zeile zeigt den **Job**, die **PatientId**, das **Auge** (eye), den **Task** und den Zeitpunkt **Hochgeladen** (upload time). Wählen Sie Zeilen aus und verwenden Sie die Zeilen-**Aktion**, um einen geparkten Scan einer Visite einer teilnehmenden Person zuzuordnen, oder **Neu laden**, um zu aktualisieren.',
      ],
      notes: ['Ausschließlich Administrator/-innen vorbehalten. Geparkte Jobs haben noch keine Verknüpfung zu einer Studienteilnehmerin/einem Studienteilnehmer, weshalb sie hier statt auf einer probandenbezogenen Seite erscheinen.'],
    },
  ],
}
