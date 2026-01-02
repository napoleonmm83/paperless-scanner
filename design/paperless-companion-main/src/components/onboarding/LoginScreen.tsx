import { useState } from "react";
import { 
  ArrowRight, 
  ArrowLeft, 
  Eye, 
  EyeOff, 
  KeyRound,
  User,
  Loader2,
  AlertCircle
} from "lucide-react";
import { Button } from "@/components/ui/button";

interface LoginScreenProps {
  onBack: () => void;
  onContinue: () => void;
}

type AuthMethod = "credentials" | "token";

const LoginScreen = ({ onBack, onContinue }: LoginScreenProps) => {
  const [authMethod, setAuthMethod] = useState<AuthMethod>("credentials");
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [apiToken, setApiToken] = useState("");
  const [showPassword, setShowPassword] = useState(false);
  const [showToken, setShowToken] = useState(false);
  const [isLoading, setIsLoading] = useState(false);
  const [error, setError] = useState("");

  const handleLogin = async () => {
    setError("");
    setIsLoading(true);
    
    // Simulate login
    await new Promise((resolve) => setTimeout(resolve, 1500));
    
    // For demo, accept any non-empty credentials
    if (authMethod === "credentials") {
      if (username && password) {
        onContinue();
      } else {
        setError("Benutzername und Passwort erforderlich");
      }
    } else {
      if (apiToken) {
        onContinue();
      } else {
        setError("API Token erforderlich");
      }
    }
    
    setIsLoading(false);
  };

  const isFormValid = authMethod === "credentials" 
    ? username.trim() && password.trim()
    : apiToken.trim();

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
            <div className="h-1 flex-1 rounded-full bg-primary" />
            <div className="h-1 flex-1 rounded-full bg-secondary" />
          </div>
        </div>
      </div>

      {/* Content */}
      <div className="flex-1 px-6 py-4 overflow-y-auto">
        {/* Icon */}
        <div className="w-16 h-16 rounded-2xl bg-primary/10 flex items-center justify-center mb-6">
          <KeyRound className="w-8 h-8 text-primary" />
        </div>

        {/* Title */}
        <h1 className="text-2xl font-bold mb-2">Anmelden</h1>
        <p className="text-muted-foreground mb-6">
          Melde dich mit deinem Paperless-ngx Konto an
        </p>

        {/* Auth method toggle */}
        <div className="flex gap-2 p-1 rounded-xl bg-secondary mb-6">
          <button
            onClick={() => setAuthMethod("credentials")}
            className={`flex-1 flex items-center justify-center gap-2 py-3 rounded-lg text-sm font-medium transition-all ${
              authMethod === "credentials"
                ? "bg-card text-foreground shadow-sm"
                : "text-muted-foreground"
            }`}
          >
            <User className="w-4 h-4" />
            Anmeldedaten
          </button>
          <button
            onClick={() => setAuthMethod("token")}
            className={`flex-1 flex items-center justify-center gap-2 py-3 rounded-lg text-sm font-medium transition-all ${
              authMethod === "token"
                ? "bg-card text-foreground shadow-sm"
                : "text-muted-foreground"
            }`}
          >
            <KeyRound className="w-4 h-4" />
            API Token
          </button>
        </div>

        {/* Form */}
        <div className="space-y-4">
          {authMethod === "credentials" ? (
            <>
              {/* Username */}
              <div className="space-y-2">
                <label className="text-sm font-medium text-muted-foreground">
                  Benutzername
                </label>
                <input
                  type="text"
                  placeholder="admin"
                  value={username}
                  onChange={(e) => setUsername(e.target.value)}
                  className="w-full h-14 px-4 rounded-xl bg-secondary border-2 border-transparent text-base placeholder:text-muted-foreground focus:outline-none focus:border-primary/50 transition-colors"
                />
              </div>

              {/* Password */}
              <div className="space-y-2">
                <label className="text-sm font-medium text-muted-foreground">
                  Passwort
                </label>
                <div className="relative">
                  <input
                    type={showPassword ? "text" : "password"}
                    placeholder="••••••••"
                    value={password}
                    onChange={(e) => setPassword(e.target.value)}
                    className="w-full h-14 px-4 pr-12 rounded-xl bg-secondary border-2 border-transparent text-base placeholder:text-muted-foreground focus:outline-none focus:border-primary/50 transition-colors"
                  />
                  <button
                    type="button"
                    onClick={() => setShowPassword(!showPassword)}
                    className="absolute right-4 top-1/2 -translate-y-1/2 text-muted-foreground hover:text-foreground"
                  >
                    {showPassword ? (
                      <EyeOff className="w-5 h-5" />
                    ) : (
                      <Eye className="w-5 h-5" />
                    )}
                  </button>
                </div>
              </div>
            </>
          ) : (
            /* API Token */
            <div className="space-y-2">
              <label className="text-sm font-medium text-muted-foreground">
                API Token
              </label>
              <div className="relative">
                <input
                  type={showToken ? "text" : "password"}
                  placeholder="Dein API Token"
                  value={apiToken}
                  onChange={(e) => setApiToken(e.target.value)}
                  className="w-full h-14 px-4 pr-12 rounded-xl bg-secondary border-2 border-transparent text-base font-mono placeholder:font-sans placeholder:text-muted-foreground focus:outline-none focus:border-primary/50 transition-colors"
                />
                <button
                  type="button"
                  onClick={() => setShowToken(!showToken)}
                  className="absolute right-4 top-1/2 -translate-y-1/2 text-muted-foreground hover:text-foreground"
                >
                  {showToken ? (
                    <EyeOff className="w-5 h-5" />
                  ) : (
                    <Eye className="w-5 h-5" />
                  )}
                </button>
              </div>
              <p className="text-xs text-muted-foreground">
                Du findest deinen API Token in den Paperless-ngx Einstellungen unter "API Token".
              </p>
            </div>
          )}

          {/* Error message */}
          {error && (
            <div className="flex items-center gap-2 p-3 rounded-xl bg-destructive/10 text-destructive text-sm">
              <AlertCircle className="w-4 h-4 shrink-0" />
              {error}
            </div>
          )}
        </div>
      </div>

      {/* Bottom action */}
      <div className="p-6">
        <Button 
          size="lg" 
          className="w-full"
          onClick={handleLogin}
          disabled={!isFormValid || isLoading}
        >
          {isLoading ? (
            <>
              <Loader2 className="w-5 h-5 mr-2 animate-spin" />
              Anmelden...
            </>
          ) : (
            <>
              Anmelden
              <ArrowRight className="w-5 h-5 ml-2" />
            </>
          )}
        </Button>
      </div>
    </div>
  );
};

export default LoginScreen;
