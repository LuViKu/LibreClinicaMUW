import type { ManualChapter } from '../manualTypes'

export const investigatorDe: ManualChapter = {
  id: 'investigator', role: 'investigator', kicker: 'Kapitel 04',
  title: 'Prüfarzt/-ärztin', deutsch: 'Investigator',
  oneLiner: 'Nimmt Teilnehmer auf, erfasst CRF-Daten, signiert Casebooks, prüft Netzhautergebnisse.',
  intro: [
    'Als **Prüfarzt/-ärztin** sind Sie für die klinische Dokumentation jedes Studienteilnehmers verantwortlich: Sie nehmen Teilnehmer auf, planen deren Visiten, erfassen die Daten der Augenuntersuchung auf den CRFs, prüfen die automatisierten Netzhaut-Scan-Ergebnisse und signieren schließlich das Casebook des Teilnehmers, um zu bestätigen, dass die Dokumentation vollständig und korrekt ist.',
  ],
  callout: {
    kind: 'accent', title: 'Ophthalmologische Kurzschreibweise',
    text: '**OD** = rechtes Auge, **OS** = linkes Auge, **OU** = beide Augen. Auf Formularen, die beide Augen erfassen, befindet sich die **Spalte OD (rechtes Auge) LINKS** und die **Spalte OS (linkes Auge) RECHTS** — dies spiegelt die Sicht der Klinikerin/des Klinikers wider, wenn sie/er der Patientin/dem Patienten gegenübersitzt.',
  },
  sections: [
    {
      id: 'inv-home', num: '1', title: 'Start', deutsch: 'Home', route: '/',
      roles: ['investigator'],
      shot: 'investigator/00-home.png',
      goal: 'Verschaffen Sie sich einen Überblick über Ihre Studie und steigen Sie in Ihre tägliche Arbeit ein.',
      steps: [
        'Nach der Anmeldung gelangen Sie auf den **Start** (Home)-Bildschirm unter `/`.',
        'Nutzen Sie die Operator-Karten, um direkt zu gefilterten Arbeitslisten zu springen — zum Beispiel verlinken *Heute* (heutige offene Visiten) und *Signaturfreigabe* (signaturbereite Teilnehmer) direkt in das Studienteilnehmer-Raster (Subject Matrix) mit dem bereits angewendeten Filter.',
        'Über die Seitenleiste erreichen Sie jederzeit das Studienteilnehmer-Raster oder das Formular **Teilnehmer aufnehmen** (Add Subject).',
      ],
    },
    {
      id: 'inv-matrix', num: '2', title: 'Studienteilnehmer', deutsch: 'Subject Matrix', route: '/subjects',
      roles: ['investigator','monitor','data-manager','administrator'],
      shot: 'investigator/01-subject-matrix.png',
      goal: 'Finden Sie einen Teilnehmer und sehen Sie auf einen Blick den Status jeder Visite.',
      steps: [
        'Öffnen Sie **Studienteilnehmer** (`/subjects`).',
        'Jede Zeile steht für einen Teilnehmer. Die ersten Spalten zeigen **Subject-ID**, **Geschlecht**, **Studienauge** (OD/OS/OU), **Group** und **Aufnahmedatum**. Die übrigen Spalten stehen für je eine geplante Visite, jeweils als farblich codierter Status-Pill; ein rotes Abzeichen zählt offene Rückfragen (Queries).',
        'Suchen Sie nach ID oder grenzen Sie mit den Filter-Chips ein (*Heute*, *Signaturfreigabe*, offene Events, alle abgeschlossen, *Signiert*). Setzen Sie das Häkchen *only with queries*, um Teilnehmer mit offenen Diskrepanzen anzuzeigen. *Export* lädt das Raster herunter.',
        'Die Spalte Subject (links) und die Aktionsspalte (rechts) bleiben fixiert, während die Visiten-Spalten scrollen. Nutzen Sie die Chevron-Schaltflächen oder *Zur aktuellsten Visite*.',
        'Klicken Sie auf den Link der **Subject-ID** oder auf **Öffnen**, um das Casebook eines Teilnehmers zu öffnen.',
      ],
      notes: [
        'Das Raster öffnet sich nach links gescrollt, sodass Sie zuerst die Spalten zur Teilnehmer-Identität sehen.',
        'Ein grüner **Signiert** (Signed)-Pill in der letzten Spalte bedeutet, dass das Casebook bereits elektronisch signiert wurde.',
      ],
    },
    {
      id: 'inv-add', num: '3', title: 'Teilnehmer aufnehmen', deutsch: 'Add Subject (enrolment)', route: '/subjects/new',
      roles: ['investigator','crc'],
      shot: 'investigator/02-add-subject.png',
      shot2: 'investigator/02b-add-subject-filled.png',
      shot2Caption: 'Das Aufnahmeformular mit Beispieldaten ausgefüllt, vor dem Speichern.',
      goal: 'Nehmen Sie einen neuen Studienteilnehmer auf.',
      steps: [
        'Öffnen Sie **Teilnehmer aufnehmen** (`/subjects/new`) über die Seitenleiste oder die Schaltfläche im Studienteilnehmer-Raster.',
        'Füllen Sie **Identifikation** aus: **Studien-Teilnehmer-ID** (erforderlich; kann mit dem Protokoll-Kurzcode vorbelegt sein, z. B. `GA-…`) und die optionale **Sekundär-ID**. *Geben Sie niemals identifizierende Daten — keinen Namen, keine Krankenhaus-ID, keine Sozialversicherungsnummer — in die Sekundär-ID ein.*',
        'Füllen Sie **Aufnahme** aus: **Aufnahmedatum** (standardmäßig heute, darf nicht in der Zukunft liegen) und **Geschlecht** (wählen Sie eine der vier Schaltflächen).',
        'Unter **Ophthalmology** können Sie optional das **Studienauge** (*nicht gesetzt* / OD / OS / OU) und ein **Screening-Datum** festlegen. Das Studienauge bestimmt die Augen-Spalte im Raster und wie die Augenuntersuchungs-CRFs dargestellt werden.',
        'Speichern Sie mit einer der drei Schaltflächen: **Speichern & nächste/n Teilnehmer/in** (leert für den nächsten), **Speichern & Abschluss** (kehrt zum Raster zurück) oder **Speichern & erste Visite planen** (öffnet das Casebook, um eine Visite zu planen).',
      ],
      notes: [
        'Die Subject-ID wird bereits während der Eingabe auf Verfügbarkeit geprüft; eine Meldung „bereits vergeben“ erscheint inline, bevor Sie absenden.',
        'Wenn der Server den Eintrag ablehnt (z. B. eine doppelte ID an diesem Standort), wird das betreffende Feld rot markiert — korrigieren Sie es und speichern Sie erneut.',
      ],
    },
    {
      id: 'inv-casebook', num: '4', title: 'Probandendetail', deutsch: 'Subject casebook (events, CRFs, retinal trends)', route: '/subjects/:id',
      roles: ['investigator','administrator'],
      shot: 'investigator/20-subject-detail.png',
      goal: 'Arbeiten Sie mit einem Teilnehmer — Identität prüfen/bearbeiten, Visiten planen, CRFs öffnen und Netzhautergebnisse prüfen.',
      steps: [
        'Öffnen Sie aus dem Studienteilnehmer-Raster einen Teilnehmer (`/subjects/:id`).',
        'Der Kopfbereich zeigt die Subject-ID und Status-Pills (**Signiert** / *Nicht signiert* / *Gesperrt*).',
        'Die **Identitäts-/Aufnahmekarte** listet die Demografie und die Studienzuordnung pro Auge auf (Zeilen OD und OS). Klicken Sie auf **Bearbeiten**, um Sekundär-ID, Geschlecht, Geburtsjahr oder Studienauge zu ändern; die Subject-ID und das Aufnahmedatum bleiben schreibgeschützt.',
        'Das Panel **Geplante Visiten / Events** listet jede geplante Visite mit Datum, Status, Datenerfassungsstufe und Anzahl offener Rückfragen auf.',
        'Um eine Visite zu öffnen, klicken Sie in ihrer Zeile auf **CRFs öffnen**. Das Überlaufmenü **⋮** bietet *Bearbeiten*, *Stornieren* und das visitenbezogene *signieren*, sofern Ihre Rolle und der Visitenstatus dies erlauben.',
        'Wenn für den Teilnehmer automatisierte Scan-Ergebnisse vorliegen, erscheint unterhalb der Visiten ein Abschnitt **Retinal-Verlauf**.',
      ],
      notes: [
        'Das Bearbeiten einer zuvor **abgeschlossenen** Visite erfordert zunächst eine ausdrückliche *Bearbeiten*-Bestätigung — abgeschlossene Visiten sind standardmäßig schreibgeschützt.',
        'Banner zu Augen-Transitionen erscheinen hier, wenn das Auge eines Teilnehmers in eine andere Studie oder aus einer anderen Studie verlagert wurde; *In andere Studie verlagern* in einer Augen-Zeile startet diesen Workflow.',
      ],
      subsections: [
        {
          title: '4a · Visite planen', deutsch: 'Schedule / open an event',
          goal: 'Fügen Sie eine Studienvisite hinzu, damit die Datenerfassung beginnen kann.',
          steps: [
            'Klicken Sie im Casebook des Teilnehmers im Kopfbereich des Events-Panels auf **Visite planen** (Schedule Event).',
            'Wählen Sie die Event-Definition, den Standort und das Startdatum und bestätigen Sie. Die neue Visite erscheint in der Events-Tabelle.',
            'Klicken Sie in der Visitenzeile auf **CRFs öffnen**, um die Event-Detailseite (`/events/:id`) zu öffnen, die jedes CRF mit Version, Status, der Angabe, ob es erforderlich ist (`*`), und einer Aktion auflistet.',
            'Für ein noch nicht begonnenes CRF klicken Sie auf **Datenerfassung starten**; für ein in Bearbeitung befindliches klicken Sie auf **CRF öffnen**.',
          ],
          notes: [
            'Eine Visite muss geplant sein, bevor Sie CRF-Daten dafür erfassen können.',
            'Sobald die Datenerfassung in jedem erforderlichen CRF abgeschlossen ist, nutzen Sie **Visite abschließen** auf der Event-Seite, um die gesamte Visite auf *Abgeschlossen* zu setzen — ein ausdrücklicher Schritt, den Sie steuern, keine automatische Kaskade.',
          ],
        },
        {
          title: '4b · Datenerfassung', deutsch: 'Enter CRF data',
          goal: 'Erfassen Sie die Befunde der Augenuntersuchung auf einem Case Report Form.',
          steps: [
            'Öffnen Sie ein CRF von der Event-Detailseite aus (`/event-crfs/:eventCrfOid`).',
            'Nutzen Sie die Abschnittsleiste auf der linken Seite, um zwischen Abschnitten zu wechseln; jedes erforderliche Item zeigt ein rotes Sternchen.',
            'Bei **bilateralen Items** hat die Zeile drei Spalten — **OD / Rechtes Auge** **links**, **OS / Linkes Auge** **rechts**, mit der Bezeichnung in der Mitte. Tragen Sie den Wert jedes Auges in seine Spalte ein.',
            'Klicken Sie auf **Entwurf speichern** (Save Draft), um zu speichern und später fortzufahren.',
            'Wenn Sie fertig sind und die erforderlichen Felder ausgefüllt sind, klicken Sie auf **CRF als abgeschlossen markieren** (Mark CRF Complete).',
          ],
          notes: [
            '**Speichern vs. Als abgeschlossen markieren:** *Entwurf speichern* speichert das Eingegebene, hält das CRF aber bearbeitbar; *als abgeschlossen markieren* validiert und setzt es auf abgeschlossen.',
            'Ein abgeschlossenes CRF ist **schreibgeschützt**. Um einen Wert zu ändern, klicken Sie auf **CRF erneut öffnen** (Reopen) — eine regulierte Aktion, die eine Bestätigung verlangt. Ein CRF eines signierten oder gesperrten Teilnehmers kann nicht bearbeitet werden. **Drucken** erzeugt eine druckfreundliche Version.',
          ],
        },
        {
          title: '4c · Teilnehmer signieren', deutsch: 'Sign the casebook',
          goal: 'Versehen Sie die vollständige Dokumentation eines Teilnehmers mit Ihrer elektronischen Signatur.',
          steps: [
            'Öffnen Sie den Teilnehmer und gehen Sie zu **Teilnehmer … signieren** (`/subjects/:id/sign`).',
            'Das Panel **Pre-Flight-Prüfungen** listet die Bedingungen auf, die vor dem Signieren erfüllt sein müssen; jeder blockierende Fehler hält die Absende-Schaltfläche deaktiviert.',
            'Prüfen Sie die Tabelle **Casebook des Teilnehmers — zu bestätigen** (jede Visite, Status, Anzahl offener Rückfragen). *PDF-Vorschau* öffnet das Casebook als PDF.',
            'Lesen Sie die Bestätigungserklärung, setzen Sie das Häkchen zur Kenntnisnahme, geben Sie Ihr **Passwort** ein und klicken Sie auf **Teilnehmer `<ID>` signieren**.',
          ],
          notes: [
            'Ihre elektronische Signatur ist das rechtsverbindliche Äquivalent Ihrer handschriftlichen Unterschrift: Sie bestätigt, dass die CRFs eine vollständige, korrekte und lückenlose Dokumentation darstellen. Sie müssen für jeden Teilnehmer, den Sie signieren, Ihr Passwort erneut eingeben.',
            'Werden Daten eines signierten Teilnehmers später geändert, fällt die betroffene Visite von *Signiert* auf *Completed* zurück und muss erneut signiert werden.',
          ],
        },
      ],
    },
    {
      id: 'inv-retinal', num: '5', title: 'Netzhaut-Auswertung', deutsch: 'Review retinal scan metrics', route: '/retinal-jobs/:id',
      roles: ['investigator'],
      shot: 'investigator/21-retinal-viewer.png',
      shotCaption: 'KPI-Kacheln, En-face-Fundus mit ETDRS-Ring-Overlay und der B-Scan-Navigator mit dem Segmentierungs-Overlay.',
      goal: 'Prüfen Sie die Ergebnisse der automatisierten OCT-Inferenz-Pipeline (Flüssigkeitsvolumina, GA-Fläche, Netzhautdicke) für einen Scan.',
      steps: [
        'Öffnen Sie einen Netzhaut-Job — aus dem Abschnitt **Retinal-Verlauf** eines Teilnehmers oder direkt unter `/retinal-jobs/:id`.',
        'Der Kopfbereich (**Netzhaut-Auswertung**) zeigt die **Lateralität** (OD / OS), die Analyseaufgabe, den Job-Status, die Modellversion, die Laufzeit und einen **Konfidenz** (confidence)-Balken.',
        'Lesen Sie die KPI-Kacheln. Bei einer Flüssigkeitsanalyse sind dies die Flüssigkeitsvolumina **IRF**, **SRF**, **PED** und **Gesamt** in mm³; eine GA-Analyse zeigt die **GA-Fläche**; Dickenaufgaben zeigen die **ONL**- / **PR**-Dicke.',
        'Das **Fundus-(En-face-)Overlay** zeigt die ETDRS-Ringe (zentral 1 / 3 / 6 mm) über dem Scan; die zugehörige Tabelle schlüsselt die Metrik pro Ring auf.',
        'Der **B-Scan-Viewer** ermöglicht es Ihnen, mit dem Segmentierungs-Overlay durch die einzelnen OCT-Schnitte zu navigieren.',
      ],
      notes: [
        'Die Ergebnisse stammen aus einer automatisierten Inferenz-Pipeline — behandeln Sie sie als Lesehilfe, nicht als Ersatz für die klinische Beurteilung.',
        'Während ein Job noch läuft, sehen Sie eine Live-Statusanzeige und laufende Meldungen (in Warteschlange / Vorverarbeitung / Segmentierung); die Ansicht aktualisiert sich, sobald jede Stufe abgeschlossen ist.',
        '**Erneut versuchen** stößt einen fehlgeschlagenen Job erneut an; **Anderen Task ausführen** analysiert denselben Scan unter einer anderen Aufgabe erneut (Flüssigkeit, GA, ONL, PR, Schichten).',
      ],
    },
    {
      id: 'inv-trends', num: '6', title: 'Retinal-Verlauf', deutsch: 'Retinal trends on the casebook', route: '/subjects/:id#retinal',
      roles: ['investigator'],
      goal: 'Verfolgen Sie die Biomarker eines Teilnehmers über die Visiten hinweg.',
      steps: [
        'Scrollen Sie im Casebook des Teilnehmers zum Abschnitt **Retinal-Verlauf** — er erscheint nur, wenn für den Teilnehmer mindestens ein Netzhaut-Job vorliegt.',
        'Wählen Sie mit dem **Aufgabe**-Selektor eine Aufgabe — *Flüssigkeit (IRF / SRF / PED)*, *GA-Fläche*, *ONL-Dicke* oder *PR-Dicke* —, um das Verlaufsdiagramm zu steuern.',
        'Unter dem Diagramm listet die **Verlaufstabelle** jeden Scan-Job mit Aufnahmedatum, Aufgabe, Auge, Status und primärer Metrik auf. Klicken Sie auf eine Spaltenüberschrift zum Sortieren; klicken Sie auf den Ansichtslink in einer Zeile, um die vollständigen Metriken dieses Jobs zu öffnen.',
      ],
    },
  ],
}
