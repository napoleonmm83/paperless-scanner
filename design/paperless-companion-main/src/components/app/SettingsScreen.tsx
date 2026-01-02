import { useState } from "react";
import { 
  Server, 
  Shield, 
  Moon, 
  Bell, 
  HelpCircle, 
  Info, 
  ChevronRight,
  Check,
  ExternalLink,
  Github,
  LogOut,
  Wifi,
  WifiOff,
  User
} from "lucide-react";
import { Switch } from "@/components/ui/switch";

const SettingsScreen = () => {
  const [darkMode, setDarkMode] = useState(false);
  const [notifications, setNotifications] = useState(true);
  const [isConnected, setIsConnected] = useState(true);

  const settingsSections = [
    {
      title: "Verbindung",
      items: [
        {
          icon: Server,
          label: "Server-Adresse",
          value: "paperless.example.com",
          type: "link" as const,
          color: "pastel-cyan",
        },
        {
          icon: isConnected ? Wifi : WifiOff,
          label: "Verbindungsstatus",
          value: isConnected ? "Verbunden" : "Nicht verbunden",
          type: "status" as const,
          status: isConnected ? "success" : "error",
          color: "pastel-green",
        },
        {
          icon: Shield,
          label: "API Token",
          value: "••••••••••••",
          type: "link" as const,
          color: "pastel-purple",
        },
      ],
    },
    {
      title: "App",
      items: [
        {
          icon: Moon,
          label: "Dark Mode",
          type: "toggle" as const,
          value: darkMode,
          onChange: setDarkMode,
          color: "pastel-yellow",
        },
        {
          icon: Bell,
          label: "Benachrichtigungen",
          type: "toggle" as const,
          value: notifications,
          onChange: setNotifications,
          color: "pastel-orange",
        },
      ],
    },
    {
      title: "Hilfe",
      items: [
        {
          icon: HelpCircle,
          label: "Hilfe & FAQ",
          type: "link" as const,
          color: "pastel-pink",
        },
        {
          icon: Github,
          label: "GitHub",
          value: "Open Source",
          type: "external" as const,
          color: "bg-secondary",
        },
        {
          icon: Info,
          label: "Version",
          value: "1.2.0",
          type: "info" as const,
          color: "bg-secondary",
        },
      ],
    },
  ];

  return (
    <div className="flex flex-col h-full overflow-y-auto bg-background">
      {/* Header */}
      <div className="px-6 pt-6 pb-4">
        <h1 className="text-3xl font-serif">Einstellungen</h1>
      </div>

      {/* User info */}
      <div className="px-6 pb-6">
        <div className="p-5 rounded-2xl bg-card border border-border flex items-center gap-4">
          <div className="w-16 h-16 rounded-2xl pastel-purple flex items-center justify-center">
            <User className="w-7 h-7 text-foreground/60" />
          </div>
          <div className="flex-1">
            <h2 className="text-lg font-semibold">Paperless User</h2>
            <p className="text-sm text-muted-foreground">admin@example.com</p>
          </div>
          <button className="w-12 h-12 rounded-2xl bg-secondary flex items-center justify-center text-muted-foreground hover:text-destructive transition-colors">
            <LogOut className="w-5 h-5" />
          </button>
        </div>
      </div>

      {/* Settings sections */}
      <div className="px-6 pb-8 space-y-6">
        {settingsSections.map((section) => (
          <div key={section.title} className="space-y-3">
            <h3 className="text-xs font-semibold uppercase tracking-wider text-muted-foreground px-1">
              {section.title}
            </h3>
            <div className="space-y-2">
              {section.items.map((item, index) => (
                <div
                  key={index}
                  className="flex items-center gap-4 p-4 rounded-2xl bg-card border border-border cursor-pointer active:scale-[0.99] transition-transform"
                >
                  <div className={`w-12 h-12 rounded-xl ${item.color} flex items-center justify-center`}>
                    <item.icon className="w-5 h-5 text-foreground/60" />
                  </div>
                  <div className="flex-1 min-w-0">
                    <p className="font-medium">{item.label}</p>
                    {item.value && item.type !== "toggle" && (
                      <p className={`text-sm truncate ${
                        item.type === "status" && item.status === "success"
                          ? "text-foreground/70"
                          : item.type === "status" && item.status === "error"
                          ? "text-destructive"
                          : "text-muted-foreground"
                      }`}>
                        {item.value}
                      </p>
                    )}
                  </div>
                  {item.type === "toggle" && (
                    <Switch
                      checked={item.value as boolean}
                      onCheckedChange={item.onChange}
                    />
                  )}
                  {item.type === "link" && (
                    <ChevronRight className="w-5 h-5 text-muted-foreground" />
                  )}
                  {item.type === "external" && (
                    <ExternalLink className="w-5 h-5 text-muted-foreground" />
                  )}
                  {item.type === "status" && item.status === "success" && (
                    <div className="w-8 h-8 rounded-full bg-pastel-green flex items-center justify-center">
                      <Check className="w-4 h-4 text-foreground/60" />
                    </div>
                  )}
                </div>
              ))}
            </div>
          </div>
        ))}
      </div>

      {/* Footer */}
      <div className="px-6 pb-8 text-center space-y-1">
        <p className="text-xs text-muted-foreground">
          Paperless Scanner • Open Source
        </p>
        <a 
          href="https://docs.paperless-ngx.com" 
          className="text-xs text-primary hover:underline"
          target="_blank"
          rel="noopener noreferrer"
        >
          Paperless-ngx Dokumentation
        </a>
      </div>
    </div>
  );
};

export default SettingsScreen;
