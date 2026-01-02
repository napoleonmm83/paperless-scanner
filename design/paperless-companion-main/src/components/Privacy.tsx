import { Shield, Lock, EyeOff, Server, Github } from "lucide-react";
import { Button } from "./ui/button";

const privacyFeatures = [
  {
    icon: EyeOff,
    title: "Keine Werbung",
    description: "Werbefrei für immer",
  },
  {
    icon: Shield,
    title: "Kein Tracking",
    description: "Keine Analyse-Tools",
  },
  {
    icon: Lock,
    title: "Keine Datensammlung",
    description: "Deine Daten bleiben privat",
  },
  {
    icon: Server,
    title: "Dein Server",
    description: "Dokumente auf deinem Server",
  },
];

const Privacy = () => {
  return (
    <section className="py-24 relative overflow-hidden">
      {/* Background gradient */}
      <div 
        className="absolute inset-0 opacity-30"
        style={{
          background: "linear-gradient(to bottom, transparent, hsl(var(--primary) / 0.05), transparent)",
        }}
      />
      
      <div className="container relative z-10">
        <div className="glass-card p-8 md:p-12 space-y-8">
          <div className="flex items-center gap-3 text-primary">
            <Shield className="w-8 h-8" />
            <span className="text-sm font-mono uppercase tracking-wider">Datenschutz</span>
          </div>

          <div className="space-y-4">
            <h2 className="text-3xl md:text-4xl font-bold">
              Deine Privatsphäre ist uns wichtig
            </h2>
            <p className="text-lg text-muted-foreground max-w-2xl">
              Paperless Scanner wurde mit Datenschutz im Fokus entwickelt. 
              Keine versteckten Tracker, keine Datensammlung – deine Dokumente 
              bleiben auf deinem Server.
            </p>
          </div>

          <div className="grid sm:grid-cols-2 lg:grid-cols-4 gap-6">
            {privacyFeatures.map((feature, index) => (
              <div 
                key={index}
                className="flex items-start gap-4 p-4 rounded-xl bg-secondary/50"
              >
                <div className="feature-icon shrink-0">
                  <feature.icon className="w-5 h-5" />
                </div>
                <div>
                  <h3 className="font-medium">{feature.title}</h3>
                  <p className="text-sm text-muted-foreground">{feature.description}</p>
                </div>
              </div>
            ))}
          </div>

          <div className="flex flex-col sm:flex-row gap-4 pt-4">
            <Button variant="outline" asChild>
              <a 
                href="https://github.com/napoleonmm83/paperless-scanner" 
                target="_blank" 
                rel="noopener noreferrer"
                className="gap-2"
              >
                <Github className="w-4 h-4" />
                Quellcode ansehen
              </a>
            </Button>
            <Button variant="ghost" asChild>
              <a 
                href="https://docs.paperless-ngx.com" 
                target="_blank" 
                rel="noopener noreferrer"
              >
                Mehr über Paperless-ngx
              </a>
            </Button>
          </div>
        </div>
      </div>
    </section>
  );
};

export default Privacy;
