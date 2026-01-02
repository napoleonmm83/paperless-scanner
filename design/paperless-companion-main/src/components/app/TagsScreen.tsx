import { useState } from "react";
import { Tag, Plus, Search, Trash2, Edit2, Hash, FileText, User, Folder } from "lucide-react";

interface TagItem {
  id: string;
  name: string;
  color: string;
  count: number;
}

interface Correspondent {
  id: string;
  name: string;
  count: number;
}

interface DocType {
  id: string;
  name: string;
  count: number;
}

const mockTags: TagItem[] = [
  { id: "1", name: "Rechnung", color: "hsl(174 72% 46%)", count: 24 },
  { id: "2", name: "Vertrag", color: "hsl(35 92% 55%)", count: 8 },
  { id: "3", name: "Steuer", color: "hsl(280 70% 50%)", count: 15 },
  { id: "4", name: "Versicherung", color: "hsl(200 70% 50%)", count: 12 },
  { id: "5", name: "Gehalt", color: "hsl(120 60% 45%)", count: 36 },
  { id: "6", name: "Medizin", color: "hsl(0 70% 55%)", count: 7 },
];

const mockCorrespondents: Correspondent[] = [
  { id: "1", name: "Stadtwerke München", count: 18 },
  { id: "2", name: "Allianz Versicherung", count: 12 },
  { id: "3", name: "Finanzamt", count: 8 },
  { id: "4", name: "Hausverwaltung", count: 15 },
];

const mockDocTypes: DocType[] = [
  { id: "1", name: "Rechnung", count: 42 },
  { id: "2", name: "Vertrag", count: 15 },
  { id: "3", name: "Brief", count: 28 },
  { id: "4", name: "Bescheid", count: 9 },
];

type TabType = "tags" | "correspondents" | "types";

const TagsScreen = () => {
  const [activeTab, setActiveTab] = useState<TabType>("tags");
  const [searchQuery, setSearchQuery] = useState("");

  const tabs = [
    { id: "tags" as TabType, label: "Tags", icon: Tag },
    { id: "correspondents" as TabType, label: "Korrespondenten", icon: User },
    { id: "types" as TabType, label: "Typen", icon: Folder },
  ];

  return (
    <div className="flex flex-col h-full">
      {/* Header */}
      <div className="px-4 py-3 bg-card space-y-3">
        <h1 className="text-lg font-semibold">Organisation</h1>

        {/* Tabs */}
        <div className="flex gap-2 overflow-x-auto pb-1">
          {tabs.map((tab) => (
            <button
              key={tab.id}
              onClick={() => setActiveTab(tab.id)}
              className={`flex items-center gap-2 px-4 py-2 rounded-lg text-sm font-medium whitespace-nowrap transition-all ${
                activeTab === tab.id
                  ? "bg-primary text-primary-foreground"
                  : "bg-secondary text-muted-foreground hover:text-foreground"
              }`}
            >
              <tab.icon className="w-4 h-4" />
              {tab.label}
            </button>
          ))}
        </div>

        {/* Search */}
        <div className="relative">
          <Search className="absolute left-3 top-1/2 -translate-y-1/2 w-5 h-5 text-muted-foreground" />
          <input
            type="text"
            placeholder="Suchen..."
            value={searchQuery}
            onChange={(e) => setSearchQuery(e.target.value)}
            className="w-full h-11 pl-10 pr-4 rounded-xl bg-secondary border-none text-sm placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-primary/50"
          />
        </div>
      </div>

      {/* Content */}
      <div className="flex-1 overflow-y-auto px-4 py-4">
        {activeTab === "tags" && (
          <div className="grid grid-cols-2 gap-3">
            {mockTags.map((tag) => (
              <div
                key={tag.id}
                className="glass-card p-4 space-y-2 active:scale-[0.98] transition-transform cursor-pointer"
              >
                <div className="flex items-center gap-2">
                  <div
                    className="w-3 h-3 rounded-full"
                    style={{ backgroundColor: tag.color }}
                  />
                  <span className="font-medium truncate">{tag.name}</span>
                </div>
                <p className="text-sm text-muted-foreground">
                  {tag.count} Dokumente
                </p>
              </div>
            ))}

            {/* Add new tag */}
            <button className="glass-card p-4 border-2 border-dashed border-border hover:border-primary/50 transition-colors flex flex-col items-center justify-center gap-2 text-muted-foreground hover:text-foreground">
              <Plus className="w-6 h-6" />
              <span className="text-sm font-medium">Neuer Tag</span>
            </button>
          </div>
        )}

        {activeTab === "correspondents" && (
          <div className="space-y-2">
            {mockCorrespondents.map((corr) => (
              <div
                key={corr.id}
                className="glass-card p-4 flex items-center gap-3 active:scale-[0.98] transition-transform cursor-pointer"
              >
                <div className="w-10 h-10 rounded-full bg-secondary flex items-center justify-center">
                  <User className="w-5 h-5 text-muted-foreground" />
                </div>
                <div className="flex-1">
                  <h3 className="font-medium">{corr.name}</h3>
                  <p className="text-sm text-muted-foreground">
                    {corr.count} Dokumente
                  </p>
                </div>
                <div className="flex gap-2">
                  <button className="w-8 h-8 rounded-lg hover:bg-secondary flex items-center justify-center">
                    <Edit2 className="w-4 h-4 text-muted-foreground" />
                  </button>
                </div>
              </div>
            ))}

            <button className="w-full glass-card p-4 border-2 border-dashed border-border hover:border-primary/50 transition-colors flex items-center justify-center gap-2 text-muted-foreground hover:text-foreground">
              <Plus className="w-5 h-5" />
              <span className="font-medium">Neuer Korrespondent</span>
            </button>
          </div>
        )}

        {activeTab === "types" && (
          <div className="space-y-2">
            {mockDocTypes.map((type) => (
              <div
                key={type.id}
                className="glass-card p-4 flex items-center gap-3 active:scale-[0.98] transition-transform cursor-pointer"
              >
                <div className="w-10 h-10 rounded-full bg-primary/10 flex items-center justify-center">
                  <FileText className="w-5 h-5 text-primary" />
                </div>
                <div className="flex-1">
                  <h3 className="font-medium">{type.name}</h3>
                  <p className="text-sm text-muted-foreground">
                    {type.count} Dokumente
                  </p>
                </div>
              </div>
            ))}

            <button className="w-full glass-card p-4 border-2 border-dashed border-border hover:border-primary/50 transition-colors flex items-center justify-center gap-2 text-muted-foreground hover:text-foreground">
              <Plus className="w-5 h-5" />
              <span className="font-medium">Neuer Dokumenttyp</span>
            </button>
          </div>
        )}
      </div>
    </div>
  );
};

export default TagsScreen;
