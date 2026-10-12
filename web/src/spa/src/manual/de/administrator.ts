import type { ManualChapter } from '../manualTypes'

export const administratorDe: ManualChapter = {
  id: 'administrator', role: 'administrator', kicker: 'Kapitel 01',
  title: 'Administrator/-in', deutsch: 'Administrator',
  oneLiner: 'System- und Studienaufbau: Nutzer, Standorte und Uploader, Studien und Prüfzentren, CRFs, Regeln, Konfiguration, Audit. Eine Obermenge jeder anderen Rolle.',
  intro: [
    'Die Rolle **Administrator/-in** ist die übergeordnete Rolle (Obermenge) in LibreClinicaMUW. Sie vereint den **Systemaufbau** (Nutzerkonten, Standorte, Uploader-Tokens, Modalitätenkatalog, Passwort-Richtlinie, geplante Jobs, Systemstatus) und den **Studienaufbau** (Anlegen von Studien und ihren Prüfzentren, Erstellen von Visiten-Definitionen und CRFs, Regeln, Datensätze, Datenimport). Überall dort, wo die Anwendung eine Ansicht auf *Administrator/-in* beschränkt, erhalten Sie Zugriff; mehrere dieser Ansichten sind für keine andere Rolle sichtbar.',
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
        'Öffnen Sie **Studienteilnehmer** (Subject Matrix) über die Startseite oder die obere Leiste (`/subjects`).',
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
        'Öffnen Sie **Nutzerverwaltung** (Manage Users) über die Startseite oder die obere Leiste (`/manage-users`).',
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
      id: 'ad-sites', num: '3', title: 'Prüfzentren', deutsch: 'Sites', route: '/sites',
      roles: ['administrator','data-manager'],
      shot: 'administrator/03-sites.png',
      goal: 'Legen Sie die Prüfzentren der aktiven Studie an und verwalten Sie sie.',
      body: [
        'Ein **Prüfzentrum** ist die Teilnahme einer Studie an einem Ort: Es gehört zu genau einer Studie und liegt an genau einem **Standort** (siehe *Standorte*). Rollen, Teilnehmende und Visiten werden je Prüfzentrum getrennt.',
      ],
      steps: [
        'Öffnen Sie **Prüfzentren** (Sites) über die Startseite oder die Seitenleiste **Studienaufbau** (`/sites`). Die Liste zeigt bei jedem Prüfzentrum seinen Standort.',
        'Klicken Sie auf **Neues Prüfzentrum** (New Site), um das Inline-Formular zum Anlegen zu öffnen.',
        'Füllen Sie die Pflichtfelder aus — *Name*, *Unique Protocol ID*, *Principal Investigator* — und wählen Sie den **Standort**, an dem das Prüfzentrum liegt. Name und Anschrift der Einrichtung übernimmt die Anwendung vom Standort; die Kontakt-E-Mail geben Sie selbst an. Klicken Sie dann auf Anlegen.',
        'Verwenden Sie bei einem bestehenden Prüfzentrum **Entfernen** (Disable) zum sanften Entfernen oder **Wiederherstellen** (Restore), um es zurückzuholen.',
        'Nur bei einer Installation mit mehreren Standorten: **Standort ändern** in der Zeile verlegt ein Prüfzentrum an einen anderen Standort. Die Einrichtungsanschrift wird dabei vom neuen Standort übernommen; die Änderung wird im Audit-Trail dokumentiert.',
      ],
      notes: [
        'Die aktive Studie muss die übergeordnete Studie der obersten Ebene sein — die Ansicht ist ausgeblendet, wenn die aktive Studie selbst ein Prüfzentrum ist. Die Verwaltung von Prüfzentren ist für Administrator/-in und Studienleitung zugänglich.',
        'Bei einer Installation mit nur einem Standort ist dieser im Formular bereits gewählt.',
        'Das Deaktivieren eines Prüfzentrums bewahrt seine eingeschlossenen Teilnehmenden und Visiten im Audit-Trail, macht sie aber unzugänglich, bis das Prüfzentrum wiederhergestellt wird; der Bestätigungsdialog weist darauf hin.',
      ],
    },
    {
      id: 'ad-build', num: '4', title: 'Studienaufbau', deutsch: 'Build Study', route: '/build-study',
      roles: ['administrator','data-manager'],
      shot: 'administrator/04-build-study.png',
      goal: 'Verfolgen Sie die Checkliste zum Studienaufbau und führen Sie jede Aufbauaufgabe zum Abschluss.',
      steps: [
        'Öffnen Sie **Studienaufbau** (Build Study) über die Startseite oder die obere Leiste (`/build-study`).',
        'Lesen Sie die Fortschrittskarte (abgeschlossene Aufgaben, Prozentsatz, Anzahl der Prüfzentren und der eingeschlossenen Teilnehmenden).',
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
        'Öffnen Sie **Datenexport** (Data Export) über die Startseite oder die obere Leiste (`/datasets`).',
        'Klicken Sie für einen schnellen Auszug auf **Schnell-ODM-Export**. Klicken Sie für einen maßgeschneiderten Auszug auf **Neuer Datensatz** und füllen Sie den Datensatz-Assistenten aus.',
        'Erweitern Sie in der Datensatz-Tabelle **View files**, **Open wizard**, um einen Datensatz zu bearbeiten (deaktiviert, sobald er ausgeführt wurde), oder **Remove** / **Restore**, um seinen Lebenszyklus zu verwalten.',
        'Klicken Sie auf **Export now** und wählen Sie ein Format — *odm*, *csv*, *tsv*, *excel*, *sas* oder *spss* — und laden Sie dann die erzeugte Datei herunter.',
        'Unter **Zeitpläne** richten Sie wiederkehrende Exporte ein: ein Format, einen Zeitpunkt (ein Quartz-Cron-Ausdruck, mit Vorlagen für täglich, wöchentlich und monatlich) und optional eine Kontakt-E-Mail, die nach jedem fertigen oder fehlgeschlagenen Lauf eine Nachricht erhält. Ein Zeitplan lässt sich bearbeiten, pausieren, fortsetzen und löschen; Änderungen gelten sofort.',
        'Ein Export, der im Hintergrund läuft (das Komplettpaket oder ein geplanter Lauf), zeigt seinen Fortschritt unter seinem Datensatz; **Export abbrechen** hält ihn an. Ein wartender Export läuft nicht mehr, ein laufender hält am nächsten Prüfpunkt an und hinterlässt keine Datei.',
      ],
      notes: [
        'Zugänglich für Administrator/-in, Studienleitung und Monitor. Aktivieren Sie **Entfernte einschließen**, um sanft gelöschte Datensätze anzuzeigen. Ein bereits ausgeführter Datensatz kann nicht bearbeitet werden — klonen Sie ihn oder legen Sie stattdessen einen neuen an.',
        'Zeitpläne ersetzen die klassische Maske *Create Scheduled Job: Export Dataset*; die Exporte eines Zeitplans laufen unter dem Konto, das ihn angelegt hat.',
      ],
    },
    {
      id: 'ad-import', num: '11', title: 'CRF-Daten importieren', deutsch: 'Import CRF Data', route: '/import-crf-data',
      roles: ['administrator','data-manager'],
      shot: 'administrator/11-import-crf-data.png',
      goal: 'Laden Sie CRF-Daten aus einer ODM-XML-Datei in einem geführten, vierstufigen Assistenten gesammelt ein.',
      steps: [
        'Öffnen Sie **CRF-Daten importieren** über die Startseite (`/import-crf-data`).',
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
        'Öffnen Sie **System-Audit-Protokoll** über die Seitenleiste **System** (`/system/audit-log`).',
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
        'Öffnen Sie **Systemstatus** über **System** in der oberen Leiste (`/admin/system-status`) — die Einstiegsseite des Bereichs. Der Eintrag steht rechts neben dem Studien-Chip, weil die Seiten dieses Bereichs die Instanz betreffen und keine Studie; die Seitenleiste **System** wechselt zwischen ihnen.',
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
        'Öffnen Sie **Passwort-Richtlinie** über die Seitenleiste **System** (`/admin/password-policy`).',
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
        'Öffnen Sie **Anwendungskonfiguration** über die Seitenleiste **System** (`/admin/config`).',
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
        'Öffnen Sie **Geplante Jobs** über die Seitenleiste **System** (`/admin/jobs`).',
        'Prüfen Sie die Statusleiste des Schedulers (Name, gestartet/Bereitschaft) und die Jobtabelle: Name, Gruppe, Status, vorherige und nächste Auslösezeiten, Beschreibung.',
        'Klicken Sie auf **Aktualisieren**, um erneut abzufragen.',
        'Ein geplanter Export aus der klassischen Maske *Create Scheduled Job* trägt die Plakette **Alter Export-Job** mit Datensatz, Intervall, Format und Kontakt-E-Mail und läuft weiter, bis er gelöscht wird. Legen Sie ihn als Zeitplan des Datensatzes neu an (**Datenexport** → **Zeitpläne**) und klicken Sie dann hier auf **Löschen**.',
      ],
      notes: ['Ausschließlich Administrator/-innen vorbehalten. Löschen lassen sich hier nur alte geplante Exporte; alle anderen Trigger gehören der Plattform und sind schreibgeschützt. Neue Export-Zeitpläne legen Sie je Datensatz unter **Datenexport** an. Die Status-Plakette ist farblich codiert (NORMAL grün, PAUSED gelb, ERROR/BLOCKED rot). Eine leere Liste zeigt *Keine Jobs*.'],
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
      id: 'ad-inbox', num: '18', title: 'Eingang', deutsch: 'Ingest inbox (all inbound files)', route: '/ingest-inbox',
      roles: ['administrator'],
      shot: 'administrator/22-ingest-inbox.png',
      goal: 'Prüfen Sie Dateien, die von einer Kamera, der Upload-Seite oder per C-STORE eingegangen sind und noch keiner Visite zugeordnet wurden.',
      steps: [
        'Öffnen Sie den **Eingang** (`/ingest-inbox`) — die studienübergreifende Übersicht aller eingegangenen Dateien: OCT-Scans, DICOM-Bilder, Fundusfotos.',
        'Filtern Sie mit den Chips nach Art (**OCT-Scan**, **DICOM-Bild**, **Fundusbild**, **Andere Datei**), nach Status oder nach einem Kennungs-/Dateinamen-Fragment. Jede Karte zeigt Vorschau, Kennungs-Hinweis, Auge, Aufnahmedatum und Herkunft.',
        '**Zuordnen …** ordnet eine Karte (oder eine Auswahl) der Visite einer teilnehmenden Person zu; **Verwerfen** markiert eine Datei mit Begründung als keine Studiendaten. **Hochladen** öffnet den Uploader für eigene Dateien.',
      ],
      notes: ['Studienleitung, Prüfärztin/-arzt und Administrator/-in dürfen zuordnen. Eine Zuordnung setzt in der Visite das Häkchen „durchgeführt“ für die Modalität; das Aufheben nimmt es zurück. Verworfene Dateien löscht die Aufbewahrungsroutine nach 30 Tagen.'],
    },
    {
      id: 'ad-locations', num: '19', title: 'Standorte', deutsch: 'Locations', route: '/admin/locations',
      roles: ['administrator'],
      goal: 'Pflegen Sie die Standorte der Plattform und legen Sie fest, wer die Gerätedateien eines Standorts sieht.',
      body: [
        'Ein **Standort** ist ein physischer Ort: eine Klinik, eine Abteilung oder ein Partnerkrankenhaus. Standorte gelten plattformweit; eine Studie ist an einem Standort aktiv, wenn sie dort ein **Prüfzentrum** hat. Dateien von Geräten gehören zum Standort ihrer Quelle und erscheinen im **Eingang** nur bei Personen, die diesem Standort zugeordnet sind — über eine Rolle an einem seiner Prüfzentren oder über eine ausdrückliche Zuweisung.',
      ],
      steps: [
        'Öffnen Sie **System** → **Standorte** (`/admin/locations`). Die Liste zeigt je Standort Kürzel, Name, Ort, die Zahl der Prüfzentren, Studien, Dateien und Personen sowie den Status.',
        'Mit **Bearbeiten** ändern Sie Name und Anschrift; das **Kürzel** ist nach dem Anlegen fest. **Deaktivieren** ist nur möglich, wenn an dem Standort keine Studie und kein Prüfzentrum mehr aktiv ist; **Wiederherstellen** macht ihn wieder aktiv.',
        'Klicken Sie auf den Namen eines Standorts, um seine Details zu öffnen: Anschrift, Studien und Prüfzentren und die **Zugewiesenen Personen**.',
        'Unter **Person hinzufügen** geben Sie einen Benutzernamen ein und klicken auf **Zuweisen**. So sieht etwa eine Fotografin ohne Studienrolle die Gerätedateien dieses Standorts im Eingang.',
        'Setzen Sie bei einer zugewiesenen Person das Häkchen **Standort-Admin**: Sie darf dann die Uploader-Tokens dieses Standorts ausstellen, ersetzen und sperren (siehe *Uploader und Tokens*). Typischerweise ist das die IT-Ansprechperson oder die Studienkoordination vor Ort. Eine Studienrolle enthält dieses Recht nicht.',
        'Der Reiter **Quellen** listet, woher Gerätedateien kommen: Uploader auf Erfassungs-PCs, DICOM-Geräte (über ihren AE-Titel) und das Remidio-Konto. Wählen Sie für eine Quelle ohne Standort den Standort aus; ihre wartenden Dateien erscheinen sofort im Eingang dieses Standorts. Die Zahl am Reiter nennt die Quellen ohne Standort.',
      ],
      notes: [
        '**Ein Standort oder mehrere** legt die IT beim Deployment fest, nicht diese Seite. Standardmäßig ist eine Installation für **einen** Standort eingerichtet: Die Seite bietet dann *Standort anlegen* nicht an und sagt das in einem Hinweis, und alles gehört automatisch zu diesem einen Standort. Für einen zweiten Standort setzt die IT `LIBRECLINICA_DEPLOYMENT_LOCATIONS=multi` und startet neu — erst, wenn jeder Erfassungs-PC seinen Token hat.',
        'Bei mehreren Standorten sieht die Dateien einer Quelle ohne Standort nur ein Systemadministrator, bis die Quelle zugeordnet ist.',
        'Ausschließlich Administrator/-innen vorbehalten. Anlegen, Bearbeiten, Deaktivieren, Wiederherstellen, Zuweisungen, das Standort-Admin-Recht und das Zuordnen von Quellen werden im System-Audit-Protokoll dokumentiert.',
      ],
    },
    {
      id: 'ad-uploaders', num: '20', title: 'Uploader und Tokens', deutsch: 'Uploaders and tokens', route: '/uploaders',
      roles: ['administrator'],
      goal: 'Geben Sie jedem Erfassungs-PC einen eigenen Token, damit seine Dateien beim richtigen Standort ankommen.',
      body: [
        'Ein **Uploader** ist das Programm auf einem Erfassungs-PC — der Export Watcher (Clarus, Spectralis) oder die Optomed Bridge —, das Gerätedateien in den Eingang bringt. Er weist sich mit einem **Token** aus, der für genau einen Standort gilt. Der Token öffnet keine Sitzung und gibt keinen Zugriff auf Studiendaten: Er erlaubt nur, für seinen Standort Visiten nachzuschlagen, Dateien hochzuladen, Heartbeats zu melden und die Worklist abzurufen.',
      ],
      steps: [
        'Öffnen Sie **Uploader und Tokens** über **System** oder die Karte auf der Startseite (`/uploaders`). Dürfen Sie mehrere Standorte verwalten, wählen Sie oben den Standort.',
        'Klicken Sie auf **Uploader anlegen** und geben Sie einen Namen ein, an dem man den PC erkennt (z. B. „Clarus-PC Raum 3“; je Standort einmalig).',
        'Der Dialog zeigt den Token **nur dieses eine Mal**. Kopieren Sie ihn, setzen Sie das Häkchen **Ich habe den Token gesichert** und schließen Sie den Dialog. Gespeichert werden nur ein Hash und die ersten Zeichen (Spalte *Token (Anfang)*); ein verlorener Token lässt sich nicht nachschlagen, nur ersetzen.',
        'Tragen Sie den Token am Erfassungs-PC ein: Tray-Symbol des Uploaders → *Settings...* → Feld **Uploader-Token** → einfügen → speichern. Geben Sie ihn nie in einer Befehlszeile an.',
        'Klicken Sie nach einigen Minuten auf **Aktualisieren**: Die Zeile zeigt dann **Zuletzt verwendet** und **Letzte Meldung**.',
        '**Token ersetzen** erzeugt sofort einen neuen Token; der alte gilt ab diesem Moment nicht mehr. Nötig ist das, wenn ein PC getauscht wird oder ein Token in falsche Hände geraten sein könnte — am besten, wenn jemand den neuen Token gleich am PC eintragen kann. **Sperren** weist den Token dauerhaft ab, etwa wenn ein PC außer Betrieb geht; *Token ersetzen* hebt eine Sperre wieder auf.',
      ],
      notes: [
        'Zugänglich für Administrator/-innen (alle Standorte) und für **Standort-Admins** (nur ihr eigener Standort), unabhängig von ihrer Studienrolle; Standort-Admins erreichen die Seite über die Karte auf der Startseite. Anlegen, Ersetzen, Sperren und Umbenennen werden im System-Audit-Protokoll dokumentiert — der Token selbst nie.',
        'Ist die Installation für einen Standort eingerichtet, arbeiten Uploader auch ohne Token weiter. Administrator/-innen sehen dann auf dieser Seite, wie viele Programme in den letzten 7 Tagen noch ohne Token gesendet haben; vor einer Umstellung auf mehrere Standorte muss dort 0 stehen.',
      ],
      subsections: [
        {
          title: 'Upload-Seite im Browser', deutsch: 'Browser upload page',
          steps: [
            'Neben dem Token zeigt der Dialog einen **Link für die Upload-Seite** (`…/upload#token=…`). Wer die Upload-Seite über diesen Link öffnet, lädt für diesen Standort hoch.',
            'Der Link ist der Token in anderer Form: Geben Sie ihn nur an Personen weiter, die an diesem Standort Aufnahmen hochladen. Die Seite entfernt den Token beim Öffnen aus der Adresszeile und behält ihn nur für die Browser-Sitzung.',
          ],
        },
        {
          title: '„Token fehlt“ oder „Token abgelehnt“', deutsch: 'HTTP 401',
          steps: [
            '**Token fehlt** (401 *uploader token required*): Die Installation ist für mehrere Standorte eingerichtet, und der PC sendet keinen Token. Stellen Sie einen aus und tragen Sie ihn ein.',
            '**Token abgelehnt** (401 *uploader token rejected*): Der Token ist unbekannt, ersetzt oder gesperrt, oder sein Standort ist deaktiviert. Prüfen Sie die Eingabe am PC oder ersetzen Sie den Token.',
          ],
          notes: [
            'Bei einer 401 bleiben alle Dateien auf dem PC liegen; nichts geht verloren. Sobald der Token stimmt, lädt der Uploader sie beim nächsten Durchlauf von selbst hoch. Weil auch sein Heartbeat abgewiesen wird, kann ein solcher PC im **Systemstatus** stumm wirken; das Tray-Symbol nennt den Grund.',
            'Die Upload-Seite im Browser bittet in diesen Fällen, den Link des Standort-Admins zu verwenden oder einen neuen anzufordern.',
          ],
        },
      ],
    },
  ],
}
