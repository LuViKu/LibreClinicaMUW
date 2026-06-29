import type { ManualChapter } from '../manualTypes'

export const gettingStartedDe: ManualChapter = {
  id: 'getting-started', role: 'common', kicker: 'Kapitel 00',
  title: 'Erste Schritte', deutsch: 'Getting started',
  oneLiner: 'Anmeldung, Profil bei der Erstanmeldung, Studien-/Standortkontext und der Navigationsrahmen — gemeinsam für jede Rolle.',
  intro: [
    'Dieses Kapitel behandelt, was alle Rollen gemeinsam haben: die Anmeldung, das Profil bei der Erstanmeldung, die Auswahl Ihrer Studie/Ihres Standorts und den Navigationsrahmen. Die rollenspezifische Arbeit beginnt in den jeweiligen rollenbezogenen Kapiteln.',
  ],
  sections: [
    {
      id: 'gs-signin', num: '1', title: 'Anmelden', deutsch: 'Signing in',
      shot: 'common/00-login.png',
      goal: 'Sich an der Plattform authentifizieren.',
      body: [
        'Die Anwendung erreichen Sie über die URL Ihrer Einrichtung (an der MedUni Wien über die von der IT bereitgestellte eCRF-Adresse). Es gibt zwei Möglichkeiten zur Authentifizierung:',
      ],
      bullets: [
        '**Institutionelles Single Sign-on (Shibboleth).** Klicken Sie auf die SSO-Schaltfläche und schließen Sie die Anmeldung beim Identity Provider der MedUni Wien ab. Die meisten klinischen Mitarbeitenden nutzen diesen Weg.',
        '**Lokales Konto.** Geben Sie Ihren **Benutzernamen** (username) und Ihr **Passwort** (password) ein — verwendet für Sponsor-Monitore, Service-Konten und den Notfallzugang (break-glass).',
      ],
      notes: [
        'Bei falschen Zugangsdaten zeigt das Formular *„Ungültiger Benutzername oder ungültiges Passwort.“* Nach zu vielen Fehlversuchen wird das Konto gesperrt — wenden Sie sich an eine Administratorin oder einen Administrator.',
      ],
    },
    {
      id: 'gs-first-login', num: '2', title: 'Erstanmeldung & Profil', deutsch: 'First login & your profile',
      shot: 'common/01-first-login.png',
      goal: 'Vervollständigen Sie Ihr Profil, bevor Sie die Anwendung erreichen.',
      body: [
        'Bei der Erstanmeldung (oder nachdem eine Administratorin bzw. ein Administrator Ihr Konto zurückgesetzt hat) werden Sie aufgefordert, Ihr **Profil** zu vervollständigen — Name, E-Mail-Adresse und Oberflächensprache — und, sofern Ihr Konto dies erfordert, Ihr **Passwort zu ändern**.',
        'Sie können die Anwendung erst erreichen, wenn das Profil vollständig ist; dadurch wird sichergestellt, dass im Audit-Trail hinter jeder Aktion ein echter Name steht.',
      ],
    },
    {
      id: 'gs-study', num: '3', title: 'Studie auswählen', deutsch: 'Choosing a study (and site)',
      shot: 'common/02-pick-study.png',
      goal: 'Begrenzen Sie Ihre Sitzung auf eine Studie und einen Standort.',
      body: [
        'Ist Ihr Konto mehr als einer Studie zugeordnet, gelangen Sie nach der Anmeldung zur **Studienauswahl** (study picker). Wählen Sie die Studie (und, sofern zutreffend, den Standort) aus, in der bzw. an dem Sie arbeiten möchten. Alles, was Sie danach tun, ist auf diese Auswahl beschränkt. Sie können später über die obere Leiste wechseln.',
      ],
    },
    {
      id: 'gs-chrome', num: '4', title: 'Navigationsrahmen', deutsch: 'The navigation chrome',
      shot: 'administrator/00-home.png',
      goal: 'Lernen Sie den auf jeder Seite gemeinsamen Rahmen kennen.',
      bullets: [
        '**Obere Leiste** (top bar) — die aktive Studie/der aktive Standort, ein Suchfeld für Teilnehmer-IDs, Ihr Name mit einem **Rollen-Chip** (rollenspezifisch farbcodiert), die Sprachanzeige und **Abmelden**.',
        '**Seitennavigation** (side navigation) — die für Ihre Rolle verfügbaren Arbeitsabläufe. Die Auswahl der Verknüpfungen ist rollenabhängig, sodass ein Monitor und eine Studienleitung unterschiedliche Menüs sehen.',
        '**Brotkrümel** (breadcrumbs) — zeigen, wo Sie sich innerhalb eines mehrstufigen Ablaufs befinden (z. B. Teilnehmer → Visite → CRF).',
      ],
      notes: [
        'Das **Start-Dashboard** (home dashboard) fasst zusammen, was Ihre Aufmerksamkeit erfordert — beispielsweise Ihnen zugewiesene Notizen und Diskrepanzen — und bietet Schnellzugriffe auf Ihre häufigsten Aufgaben.',
      ],
    },
    {
      id: 'gs-language', num: '5', title: 'Sprache & Abmelden', deutsch: 'Language & logging out',
      goal: 'Verstehen Sie die deutschsprachige Oberfläche und die Hygiene an gemeinsam genutzten Arbeitsplätzen.',
      body: [
        'Die Oberfläche ist für klinische Mitarbeitende **vorrangig deutschsprachig** (z. B. *Modalitäten*, *Übernehmen*); einige administrative Bildschirme bleiben in Englisch. Ihre Sprache legen Sie in Ihrem Profil fest.',
        'Verwenden Sie **Abmelden** (Log out) in der oberen Leiste. An gemeinsam genutzten Arbeitsplätzen melden Sie sich stets ab — der Audit-Trail ordnet jeden Eintrag der angemeldeten Person zu.',
      ],
    },
  ],
}
