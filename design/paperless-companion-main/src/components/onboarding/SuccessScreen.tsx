import { CheckCircle2, PartyPopper, ArrowRight } from "lucide-react";
import { Button } from "@/components/ui/button";

interface SuccessScreenProps {
  onComplete: () => void;
}

const SuccessScreen = ({ onComplete }: SuccessScreenProps) => {
  return (
    <div className="flex flex-col h-full bg-background relative overflow-hidden">
      {/* Background effects */}
      <div className="absolute inset-0 pointer-events-none">
        <div 
          className="absolute top-1/3 left-1/2 -translate-x-1/2 w-96 h-96 rounded-full"
          style={{ background: "radial-gradient(ellipse at center, hsl(var(--primary) / 0.2) 0%, transparent 60%)" }}
        />
      </div>

      {/* Progress bar complete */}
      <div className="flex items-center gap-3 px-4 py-4">
        <div className="w-10" />
        <div className="flex-1">
          <div className="flex gap-1">
            <div className="h-1 flex-1 rounded-full bg-primary" />
            <div className="h-1 flex-1 rounded-full bg-primary" />
            <div className="h-1 flex-1 rounded-full bg-primary" />
          </div>
        </div>
      </div>

      {/* Content */}
      <div className="flex-1 flex flex-col items-center justify-center px-8 relative z-10">
        {/* Success icon */}
        <div className="relative mb-8">
          <div className="w-28 h-28 rounded-full bg-primary/20 flex items-center justify-center">
            <CheckCircle2 className="w-16 h-16 text-primary" />
          </div>
          <div className="absolute -top-2 -right-2 w-10 h-10 rounded-full bg-accent flex items-center justify-center shadow-lg animate-bounce">
            <PartyPopper className="w-5 h-5 text-accent-foreground" />
          </div>
        </div>

        {/* Text */}
        <div className="text-center space-y-4 mb-12">
          <h1 className="text-3xl font-bold text-foreground">
            Alles bereit!
          </h1>
          <p className="text-muted-foreground text-lg leading-relaxed max-w-xs">
            Du bist verbunden und kannst jetzt Dokumente scannen und hochladen.
          </p>
        </div>

        {/* Quick stats */}
        <div className="grid grid-cols-2 gap-4 w-full max-w-xs mb-8">
          <div className="glass-card p-4 text-center">
            <div className="text-2xl font-bold text-primary">0</div>
            <div className="text-xs text-muted-foreground">Heute gescannt</div>
          </div>
          <div className="glass-card p-4 text-center">
            <div className="text-2xl font-bold text-primary">✓</div>
            <div className="text-xs text-muted-foreground">Verbunden</div>
          </div>
        </div>

        {/* Tips */}
        <div className="w-full max-w-xs space-y-2">
          <p className="text-xs font-medium text-muted-foreground uppercase tracking-wider text-center mb-3">
            Schnellstart-Tipps
          </p>
          {[
            "Halte das Dokument flach und gut beleuchtet",
            "Die App erkennt die Kanten automatisch",
            "Tags kannst du vor dem Upload auswählen",
          ].map((tip, i) => (
            <div 
              key={i}
              className="flex items-center gap-3 px-4 py-3 rounded-xl bg-card/60 border border-border/50"
            >
              <div className="w-5 h-5 rounded-full bg-primary/20 flex items-center justify-center text-xs font-medium text-primary">
                {i + 1}
              </div>
              <span className="text-sm text-muted-foreground">{tip}</span>
            </div>
          ))}
        </div>
      </div>

      {/* Bottom action */}
      <div className="p-6 pb-10">
        <Button 
          size="lg" 
          className="w-full"
          onClick={onComplete}
        >
          App starten
          <ArrowRight className="w-5 h-5 ml-2" />
        </Button>
      </div>
    </div>
  );
};

export default SuccessScreen;
