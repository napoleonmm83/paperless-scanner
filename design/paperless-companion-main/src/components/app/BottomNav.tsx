import { Home, FileText, User, Plus, Tag } from "lucide-react";
import type { Screen } from "@/pages/AppDemo";

interface BottomNavProps {
  currentScreen: Screen;
  onNavigate: (screen: Screen) => void;
}

const BottomNav = ({ currentScreen, onNavigate }: BottomNavProps) => {
  return (
    <div className="flex items-center justify-center py-4 px-6 bg-transparent">
      <div className="flex items-center bg-primary rounded-full p-1.5 shadow-elevated">
        {/* Home */}
        <button
          onClick={() => onNavigate("home")}
          className={`w-11 h-11 rounded-full flex items-center justify-center transition-all duration-200 ${
            currentScreen === "home" 
              ? "bg-card text-foreground" 
              : "text-primary-foreground hover:bg-primary-foreground/10"
          }`}
        >
          <Home className="w-5 h-5" />
        </button>

        {/* Documents */}
        <button
          onClick={() => onNavigate("documents")}
          className={`w-11 h-11 rounded-full flex items-center justify-center transition-all duration-200 ${
            currentScreen === "documents" 
              ? "bg-card text-foreground" 
              : "text-primary-foreground hover:bg-primary-foreground/10"
          }`}
        >
          <FileText className="w-5 h-5" />
        </button>

        {/* Center - Scan FAB */}
        <button
          onClick={() => onNavigate("scanner")}
          className={`w-14 h-14 rounded-full flex items-center justify-center mx-1 transition-all duration-200 active:scale-95 ${
            currentScreen === "scanner"
              ? "bg-card text-foreground"
              : "bg-primary-foreground text-primary"
          }`}
        >
          <Plus className="w-6 h-6" />
        </button>

        {/* Labels */}
        <button
          onClick={() => onNavigate("labels")}
          className={`w-11 h-11 rounded-full flex items-center justify-center transition-all duration-200 ${
            currentScreen === "labels" 
              ? "bg-card text-foreground" 
              : "text-primary-foreground hover:bg-primary-foreground/10"
          }`}
        >
          <Tag className="w-5 h-5" />
        </button>

        {/* Settings */}
        <button
          onClick={() => onNavigate("settings")}
          className={`w-11 h-11 rounded-full flex items-center justify-center transition-all duration-200 ${
            currentScreen === "settings" 
              ? "bg-card text-foreground" 
              : "text-primary-foreground hover:bg-primary-foreground/10"
          }`}
        >
          <User className="w-5 h-5" />
        </button>
      </div>
    </div>
  );
};

export default BottomNav;
