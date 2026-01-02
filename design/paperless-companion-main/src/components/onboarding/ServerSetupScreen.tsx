import { useState } from "react";
import { 
  Server, 
  ArrowRight, 
  ArrowLeft, 
  Shield
} from "lucide-react";
import { Button } from "@/components/ui/button";

interface ServerSetupScreenProps {
  onBack: () => void;
  onContinue: () => void;
}

const ServerSetupScreen = ({ onBack, onContinue }: ServerSetupScreenProps) => {
  const [serverUrl, setServerUrl] = useState("");

  return (
    <div className="flex flex-col h-full bg-background">
      {/* Header */}
      <div className="flex items-center gap-3 px-4 py-4">
        <button
          onClick={onBack}
          className="w-10 h-10 rounded-xl hover:bg-secondary flex items-center justify-center"
        >
          <ArrowLeft className="w-6 h-6" />
        </button>
        <div className="flex-1">
          <div className="flex gap-1">
            <div className="h-1 flex-1 rounded-full bg-primary" />
            <div className="h-1 flex-1 rounded-full bg-secondary" />
            <div className="h-1 flex-1 rounded-full bg-secondary" />
          </div>
        </div>
      </div>

      {/* Content */}
      <div className="flex-1 px-6 py-4">
        {/* Icon */}
        <div className="w-16 h-16 rounded-2xl bg-primary/10 flex items-center justify-center mb-6">
          <Server className="w-8 h-8 text-primary" />
        </div>

        {/* Title */}
        <h1 className="text-2xl font-bold mb-2">Server verbinden</h1>
        <p className="text-muted-foreground mb-8">
          Gib die URL deiner Paperless-ngx Installation ein
        </p>

        {/* Server URL Input */}
        <div className="space-y-4">
          <div className="space-y-2">
            <label className="text-sm font-medium text-muted-foreground">
              Server-URL
            </label>
            <input
              type="url"
              placeholder="https://paperless.example.com"
              value={serverUrl}
              onChange={(e) => setServerUrl(e.target.value)}
              className="w-full h-14 px-4 rounded-xl bg-secondary border-2 border-transparent text-base placeholder:text-muted-foreground focus:outline-none focus:border-primary/50 transition-colors"
            />
          </div>

          {/* Hint */}
          <div className="flex items-start gap-3 p-4 rounded-xl bg-card border border-border">
            <Shield className="w-5 h-5 text-primary shrink-0 mt-0.5" />
            <div className="text-sm">
              <p className="font-medium mb-1">Sichere Verbindung empfohlen</p>
              <p className="text-muted-foreground">
                Verwende HTTPS für eine verschlüsselte Verbindung zu deinem Server.
              </p>
            </div>
          </div>
        </div>
      </div>

      {/* Bottom action */}
      <div className="p-6">
        <Button 
          size="lg" 
          className="w-full"
          onClick={onContinue}
          disabled={!serverUrl.trim()}
        >
          Weiter
          <ArrowRight className="w-5 h-5 ml-2" />
        </Button>
      </div>
    </div>
  );
};

export default ServerSetupScreen;
