import { Button } from "@/components/ui/button";
import PhoneMockup from "./PhoneMockup";
import { ExternalLink, Github, Shield } from "lucide-react";

const Hero = () => {
  return (
    <section className="relative min-h-screen flex items-center py-20 overflow-hidden">
      {/* Background effects */}
      <div className="absolute inset-0 pointer-events-none">
        <div 
          className="absolute top-1/4 left-1/4 w-96 h-96 rounded-full animate-pulse-slow"
          style={{ background: "var(--gradient-glow)" }}
        />
        <div 
          className="absolute bottom-1/4 right-1/4 w-80 h-80 rounded-full animate-pulse-slow"
          style={{ 
            background: "radial-gradient(ellipse at center, hsl(35 92% 55% / 0.1) 0%, transparent 70%)",
            animationDelay: "2s"
          }}
        />
      </div>

      <div className="container relative z-10">
        <div className="grid lg:grid-cols-2 gap-12 lg:gap-20 items-center">
          {/* Content */}
          <div className="space-y-8 text-center lg:text-left">
            <div className="space-y-4">
              <div className="inline-flex items-center gap-2 px-4 py-2 rounded-full bg-primary/10 border border-primary/20 text-primary text-sm font-medium animate-fade-up">
                <Shield className="w-4 h-4" />
                Open Source & Datenschutzfreundlich
              </div>
              
              <h1 className="text-4xl md:text-5xl lg:text-6xl font-bold leading-tight animate-fade-up-delay-1">
                <span className="gradient-text">Paperless</span>{" "}
                <span className="text-foreground">Scanner</span>
              </h1>
              
              <p className="text-lg md:text-xl text-muted-foreground max-w-xl mx-auto lg:mx-0 animate-fade-up-delay-2 text-balance">
                Der mobile Begleiter für deine Paperless-ngx Installation. 
                Scanne Dokumente unterwegs und lade sie direkt in dein papierloses Archiv.
              </p>
            </div>

            <div className="flex flex-col sm:flex-row gap-4 justify-center lg:justify-start animate-fade-up-delay-3">
              <Button size="lg" asChild>
                <a 
                  href="https://play.google.com/store" 
                  target="_blank" 
                  rel="noopener noreferrer"
                  className="gap-2"
                >
                  <svg viewBox="0 0 24 24" className="w-5 h-5" fill="currentColor">
                    <path d="M3.609 1.814L13.792 12 3.61 22.186a.996.996 0 01-.61-.92V2.734a1 1 0 01.609-.92zm10.89 10.893l2.302 2.302-10.937 6.333 8.635-8.635zm3.199-3.198l2.807 1.626a1 1 0 010 1.73l-2.808 1.626L15.206 12l2.492-2.491zM5.864 2.658L16.8 8.99l-2.302 2.302-8.634-8.634z"/>
                  </svg>
                  Google Play
                </a>
              </Button>
              
              <Button variant="glass" size="lg" asChild>
                <a 
                  href="https://github.com/napoleonmm83/paperless-scanner" 
                  target="_blank" 
                  rel="noopener noreferrer"
                  className="gap-2"
                >
                  <Github className="w-5 h-5" />
                  GitHub
                </a>
              </Button>
            </div>

            <div className="flex items-center gap-6 justify-center lg:justify-start text-sm text-muted-foreground animate-fade-up-delay-3">
              <div className="flex items-center gap-2">
                <div className="w-2 h-2 rounded-full bg-primary animate-pulse" />
                Keine Werbung
              </div>
              <div className="flex items-center gap-2">
                <div className="w-2 h-2 rounded-full bg-primary animate-pulse" style={{ animationDelay: "0.5s" }} />
                Kein Tracking
              </div>
            </div>
          </div>

          {/* Phone mockup */}
          <div className="flex justify-center lg:justify-end">
            <PhoneMockup />
          </div>
        </div>
      </div>
    </section>
  );
};

export default Hero;
