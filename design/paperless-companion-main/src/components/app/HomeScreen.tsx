import { 
  FileText, 
  Clock, 
  Tag,
  TrendingUp,
  Calendar,
  ChevronRight,
  Inbox
} from "lucide-react";

interface HomeScreenProps {
  onNavigate: (screen: "home" | "scanner" | "documents" | "settings") => void;
}

const recentDocs = [
  { id: "1", title: "Stromrechnung März", date: "Vor 2 Std.", tag: "Rechnung", tagColor: "hsl(180 50% 45%)" },
  { id: "2", title: "Mietvertrag 2024", date: "Gestern", tag: "Vertrag", tagColor: "hsl(45 80% 50%)" },
  { id: "3", title: "Steuerbescheid", date: "Vor 3 Tagen", tag: "Steuer", tagColor: "hsl(270 50% 55%)" },
];

const stats = [
  { label: "Dokumente", value: "247", icon: FileText, color: "pastel-cyan" },
  { label: "Diesen Monat", value: "12", icon: Calendar, color: "pastel-yellow" },
  { label: "Ausstehend", value: "3", icon: Inbox, color: "pastel-purple" },
];

const HomeScreen = ({ onNavigate }: HomeScreenProps) => {
  return (
    <div className="flex flex-col h-full bg-background overflow-y-auto">
      {/* Header */}
      <div className="px-6 pt-6 pb-4">
        <p className="text-sm text-muted-foreground mb-1">Willkommen zurück</p>
        <h1 className="text-3xl font-serif">Dein Archiv</h1>
      </div>

      {/* Stats Grid */}
      <div className="px-6 pb-6">
        <div className="grid grid-cols-3 gap-3">
          {stats.map((stat) => (
            <div 
              key={stat.label}
              className={`${stat.color} rounded-2xl p-4 flex flex-col items-center justify-center`}
            >
              <stat.icon className="w-5 h-5 text-foreground/60 mb-2" />
              <span className="text-2xl font-bold text-foreground">{stat.value}</span>
              <span className="text-xs text-foreground/60 mt-0.5">{stat.label}</span>
            </div>
          ))}
        </div>
      </div>

      {/* Quick Actions */}
      <div className="px-6 pb-4">
        <div className="flex gap-3">
          <button 
            onClick={() => onNavigate("scanner")}
            className="flex-1 h-14 rounded-2xl bg-primary text-primary-foreground font-medium flex items-center justify-center gap-2 active:scale-[0.98] transition-transform"
          >
            <TrendingUp className="w-5 h-5" />
            Neuer Scan
          </button>
          <button 
            onClick={() => onNavigate("documents")}
            className="flex-1 h-14 rounded-2xl bg-card border border-border font-medium flex items-center justify-center gap-2 active:scale-[0.98] transition-transform"
          >
            <FileText className="w-5 h-5" />
            Alle Dokumente
          </button>
        </div>
      </div>

      {/* Recent Documents */}
      <div className="px-6 pb-6 flex-1">
        <div className="flex items-center justify-between mb-4">
          <h2 className="text-lg font-semibold">Zuletzt hinzugefügt</h2>
          <button 
            onClick={() => onNavigate("documents")}
            className="text-sm text-muted-foreground flex items-center gap-1"
          >
            Alle
            <ChevronRight className="w-4 h-4" />
          </button>
        </div>

        <div className="space-y-3">
          {recentDocs.map((doc) => (
            <div 
              key={doc.id}
              className="flex items-center gap-4 p-4 rounded-2xl bg-card border border-border active:scale-[0.99] transition-transform cursor-pointer"
            >
              <div className="w-12 h-12 rounded-xl pastel-green flex items-center justify-center shrink-0">
                <FileText className="w-5 h-5 text-foreground/60" />
              </div>
              <div className="flex-1 min-w-0">
                <h3 className="font-medium truncate">{doc.title}</h3>
                <div className="flex items-center gap-2 mt-1">
                  <span className="text-xs text-muted-foreground flex items-center gap-1">
                    <Clock className="w-3 h-3" />
                    {doc.date}
                  </span>
                  <span 
                    className="text-xs px-2 py-0.5 rounded-full"
                    style={{ backgroundColor: `${doc.tagColor}20`, color: doc.tagColor }}
                  >
                    {doc.tag}
                  </span>
                </div>
              </div>
              <ChevronRight className="w-5 h-5 text-muted-foreground shrink-0" />
            </div>
          ))}
        </div>
      </div>

      {/* Activity hint */}
      <div className="px-6 pb-6">
        <div className="p-4 rounded-2xl bg-pastel-orange/50 border border-border">
          <div className="flex items-start gap-3">
            <Tag className="w-5 h-5 text-foreground/60 mt-0.5" />
            <div>
              <p className="text-sm font-medium">3 Dokumente ohne Tags</p>
              <p className="text-xs text-muted-foreground mt-0.5">
                Ordne Tags zu für bessere Organisation
              </p>
            </div>
          </div>
        </div>
      </div>
    </div>
  );
};

export default HomeScreen;
