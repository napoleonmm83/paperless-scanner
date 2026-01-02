import { useState } from "react";
import { FileText, ArrowRight, Sparkles } from "lucide-react";
import { Button } from "@/components/ui/button";

interface WelcomeScreenProps {
  onContinue: () => void;
}

const WelcomeScreen = ({ onContinue }: WelcomeScreenProps) => {
  return (
    <div className="flex flex-col h-full bg-background relative overflow-hidden">
      {/* Background effects */}
      <div className="absolute inset-0 pointer-events-none">
        <div 
          className="absolute top-1/4 left-1/2 -translate-x-1/2 w-80 h-80 rounded-full animate-pulse-slow"
          style={{ background: "radial-gradient(ellipse at center, hsl(var(--primary) / 0.2) 0%, transparent 70%)" }}
        />
        <div 
          className="absolute bottom-1/3 left-1/4 w-48 h-48 rounded-full animate-pulse-slow"
          style={{ 
            background: "radial-gradient(ellipse at center, hsl(var(--accent) / 0.15) 0%, transparent 70%)",
            animationDelay: "1s"
          }}
        />
      </div>

      {/* Content */}
      <div className="flex-1 flex flex-col items-center justify-center px-8 relative z-10">
        {/* Logo */}
        <div className="relative mb-8">
          <div className="w-28 h-28 rounded-3xl bg-gradient-to-br from-primary to-primary/60 flex items-center justify-center shadow-2xl shadow-primary/30">
            <FileText className="w-14 h-14 text-primary-foreground" />
          </div>
          <div className="absolute -top-2 -right-2 w-8 h-8 rounded-full bg-accent flex items-center justify-center shadow-lg">
            <Sparkles className="w-4 h-4 text-accent-foreground" />
          </div>
        </div>

        {/* Text */}
        <div className="text-center space-y-4 mb-12">
          <h1 className="text-3xl font-bold">
            <span className="gradient-text">Paperless</span>{" "}
            <span className="text-foreground">Scanner</span>
          </h1>
          <p className="text-muted-foreground text-lg leading-relaxed max-w-xs">
            Der mobile Begleiter für deine Paperless-ngx Installation
          </p>
        </div>

        {/* Features */}
        <div className="space-y-3 mb-12 w-full max-w-xs">
          {[
            "Intelligentes Scannen mit Kantenerkennung",
            "Multi-Page Dokumente erstellen",
            "Direkt in Paperless-ngx hochladen",
          ].map((feature, i) => (
            <div 
              key={i}
              className="flex items-center gap-3 px-4 py-3 rounded-xl bg-card/60 border border-border/50"
              style={{ animationDelay: `${i * 0.1}s` }}
            >
              <div className="w-2 h-2 rounded-full bg-primary" />
              <span className="text-sm text-muted-foreground">{feature}</span>
            </div>
          ))}
        </div>
      </div>

      {/* Bottom action */}
      <div className="p-6 pb-10">
        <Button 
          size="lg" 
          className="w-full"
          onClick={onContinue}
        >
          Los geht's
          <ArrowRight className="w-5 h-5 ml-2" />
        </Button>
        
        <p className="text-center text-xs text-muted-foreground mt-4">
          Du benötigst eine eigene Paperless-ngx Installation
        </p>
      </div>
    </div>
  );
};

export default WelcomeScreen;
