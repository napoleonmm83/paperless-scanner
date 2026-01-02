import { useState } from "react";
import { FileText, Search, Check, Clock, ChevronRight, Filter } from "lucide-react";

interface Document {
  id: string;
  title: string;
  pages: number;
  date: string;
  status: "uploaded" | "uploading" | "pending";
  tags: string[];
  correspondent?: string;
  tagColor?: string;
}

const mockDocuments: Document[] = [
  {
    id: "1",
    title: "Rechnung Strom 2024",
    pages: 2,
    date: "Heute, 14:32",
    status: "uploaded",
    tags: ["Rechnung"],
    correspondent: "Stadtwerke",
    tagColor: "hsl(180 50% 45%)",
  },
  {
    id: "2",
    title: "Mietvertrag Anlage",
    pages: 5,
    date: "Heute, 10:15",
    status: "uploading",
    tags: ["Vertrag"],
    correspondent: "Hausverwaltung",
    tagColor: "hsl(45 80% 50%)",
  },
  {
    id: "3",
    title: "Gehaltsabrechnung Dez",
    pages: 1,
    date: "Gestern",
    status: "uploaded",
    tags: ["Gehalt"],
    correspondent: "Arbeitgeber",
    tagColor: "hsl(140 50% 45%)",
  },
  {
    id: "4",
    title: "Versicherung KFZ",
    pages: 3,
    date: "Gestern",
    status: "pending",
    tags: ["Versicherung"],
    tagColor: "hsl(200 60% 50%)",
  },
  {
    id: "5",
    title: "Steuerbescheid 2023",
    pages: 8,
    date: "20.12.2024",
    status: "uploaded",
    tags: ["Steuer"],
    correspondent: "Finanzamt",
    tagColor: "hsl(270 50% 55%)",
  },
];

const DocumentsScreen = () => {
  const [searchQuery, setSearchQuery] = useState("");
  const [filter, setFilter] = useState<"all" | "pending" | "uploaded">("all");

  const filteredDocs = mockDocuments.filter((doc) => {
    if (filter === "pending" && doc.status === "uploaded") return false;
    if (filter === "uploaded" && doc.status !== "uploaded") return false;
    if (searchQuery && !doc.title.toLowerCase().includes(searchQuery.toLowerCase())) return false;
    return true;
  });

  const getStatusIndicator = (status: Document["status"]) => {
    switch (status) {
      case "uploaded":
        return (
          <div className="w-8 h-8 rounded-full bg-pastel-green flex items-center justify-center">
            <Check className="w-4 h-4 text-foreground/60" />
          </div>
        );
      case "uploading":
        return (
          <div className="w-8 h-8 rounded-full border-2 border-primary border-t-transparent animate-spin" />
        );
      case "pending":
        return (
          <div className="w-8 h-8 rounded-full bg-pastel-yellow flex items-center justify-center">
            <Clock className="w-4 h-4 text-foreground/60" />
          </div>
        );
    }
  };

  return (
    <div className="flex flex-col h-full bg-background">
      {/* Header */}
      <div className="px-6 pt-6 pb-4">
        <h1 className="text-3xl font-serif">Dokumente</h1>
        <p className="text-sm text-muted-foreground mt-1">{mockDocuments.length} Dokumente</p>
      </div>

      {/* Search */}
      <div className="px-6 pb-4">
        <div className="flex items-center gap-3 h-14 px-5 rounded-2xl bg-card border border-border">
          <Search className="w-5 h-5 text-muted-foreground" />
          <input
            type="text"
            placeholder="Dokumente suchen..."
            value={searchQuery}
            onChange={(e) => setSearchQuery(e.target.value)}
            className="flex-1 bg-transparent text-base placeholder:text-muted-foreground/50 focus:outline-none"
          />
          <button className="w-10 h-10 -mr-2 rounded-xl flex items-center justify-center hover:bg-secondary transition-colors">
            <Filter className="w-5 h-5 text-muted-foreground" />
          </button>
        </div>
      </div>

      {/* Filter tabs */}
      <div className="px-6 pb-4">
        <div className="flex gap-2">
          {[
            { id: "all", label: "Alle" },
            { id: "pending", label: "Ausstehend" },
            { id: "uploaded", label: "Hochgeladen" },
          ].map((tab) => (
            <button
              key={tab.id}
              onClick={() => setFilter(tab.id as typeof filter)}
              className={`px-4 py-2.5 rounded-full text-sm font-medium transition-all ${
                filter === tab.id
                  ? "bg-primary text-primary-foreground"
                  : "bg-secondary text-muted-foreground hover:text-foreground"
              }`}
            >
              {tab.label}
            </button>
          ))}
        </div>
      </div>

      {/* Document list */}
      <div className="flex-1 overflow-y-auto px-6 pb-6 space-y-3">
        {filteredDocs.map((doc) => (
          <div
            key={doc.id}
            className="flex items-center gap-4 p-4 rounded-2xl bg-card border border-border active:scale-[0.99] transition-transform cursor-pointer"
          >
            {/* Icon */}
            <div className="w-12 h-12 rounded-xl pastel-cyan flex items-center justify-center shrink-0">
              <FileText className="w-5 h-5 text-foreground/60" />
            </div>

            {/* Content */}
            <div className="flex-1 min-w-0">
              <h3 className="font-medium truncate">{doc.title}</h3>
              <div className="flex items-center gap-2 mt-1">
                <span className="text-xs text-muted-foreground">
                  {doc.pages} Seite{doc.pages > 1 ? "n" : ""} • {doc.date}
                </span>
              </div>
              {doc.tags.length > 0 && (
                <div className="flex gap-1.5 mt-2">
                  {doc.tags.map((tag) => (
                    <span
                      key={tag}
                      className="text-xs px-2 py-0.5 rounded-full"
                      style={{ 
                        backgroundColor: `${doc.tagColor}20`, 
                        color: doc.tagColor 
                      }}
                    >
                      {tag}
                    </span>
                  ))}
                </div>
              )}
            </div>

            {/* Status & Arrow */}
            <div className="flex items-center gap-2 shrink-0">
              {getStatusIndicator(doc.status)}
              <ChevronRight className="w-5 h-5 text-muted-foreground" />
            </div>
          </div>
        ))}
      </div>
    </div>
  );
};

export default DocumentsScreen;
