import { 
  ScanLine, 
  FileStack, 
  Tags, 
  Upload, 
  Palette, 
  Server 
} from "lucide-react";
import FeatureCard from "./FeatureCard";

const features = [
  {
    icon: ScanLine,
    title: "Intelligentes Scannen",
    description: "Professionelle Dokumentenerfassung mit KI-Unterstützung",
    features: [
      "Automatische Kantenerkennung",
      "Perspektivkorrektur",
      "Optimierte Bildqualität",
    ],
  },
  {
    icon: FileStack,
    title: "Multi-Page Dokumente",
    description: "Erstelle mehrseitige PDFs mit Leichtigkeit",
    features: [
      "Bis zu 20 Seiten scannen",
      "Seiten neu anordnen oder löschen",
      "Automatische PDF-Erstellung",
    ],
  },
  {
    icon: Tags,
    title: "Organisation",
    description: "Halte deine Dokumente perfekt organisiert",
    features: [
      "Tags auswählen oder erstellen",
      "Dokumenttyp zuweisen",
      "Korrespondent festlegen",
    ],
  },
  {
    icon: Upload,
    title: "Batch-Import",
    description: "Importiere mehrere Dokumente auf einmal",
    features: [
      "Bilder aus Galerie importieren",
      "Fortschrittsanzeige beim Upload",
      "Hintergrund-Upload",
    ],
  },
  {
    icon: Palette,
    title: "Modernes Design",
    description: "Nutzerfreundliche Material 3 Oberfläche",
    features: [
      "Material 3 Design",
      "Dark Mode Unterstützung",
      "Einfache Bedienung",
    ],
  },
  {
    icon: Server,
    title: "Voraussetzung",
    description: "Benötigt eigene Paperless-ngx Installation",
    features: [
      "Open-Source DMS",
      "Selbstgehostet",
      "Volle Kontrolle",
    ],
  },
];

const Features = () => {
  return (
    <section className="py-24 relative">
      <div className="container">
        <div className="text-center space-y-4 mb-16">
          <h2 className="text-3xl md:text-4xl font-bold">
            Alles was du brauchst
          </h2>
          <p className="text-lg text-muted-foreground max-w-2xl mx-auto">
            Paperless Scanner bietet alle Funktionen für ein effizientes 
            Dokumentenmanagement direkt auf deinem Smartphone.
          </p>
        </div>

        <div className="grid md:grid-cols-2 lg:grid-cols-3 gap-6">
          {features.map((feature, index) => (
            <div 
              key={index}
              style={{ animationDelay: `${index * 0.1}s` }}
              className="animate-fade-up opacity-0"
            >
              <FeatureCard {...feature} />
            </div>
          ))}
        </div>
      </div>
    </section>
  );
};

export default Features;
