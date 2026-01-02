import { useState } from "react";
import { 
  X, 
  FileText, 
  Tag, 
  User, 
  Folder,
  ChevronDown,
  Plus,
  Check,
  Upload,
  Sparkles
} from "lucide-react";
import { Button } from "@/components/ui/button";

interface ScanMetadataSheetProps {
  isOpen: boolean;
  onClose: () => void;
  onUpload: () => void;
  pageCount: number;
}

const availableTags = [
  { id: "1", name: "Rechnung", color: "hsl(180 50% 45%)" },
  { id: "2", name: "Vertrag", color: "hsl(45 80% 50%)" },
  { id: "3", name: "Steuer", color: "hsl(270 50% 55%)" },
  { id: "4", name: "Versicherung", color: "hsl(200 60% 50%)" },
  { id: "5", name: "Gehalt", color: "hsl(140 50% 45%)" },
  { id: "6", name: "Medizin", color: "hsl(340 60% 55%)" },
];

const correspondents = [
  { id: "1", name: "Stadtwerke München" },
  { id: "2", name: "Allianz Versicherung" },
  { id: "3", name: "Finanzamt" },
  { id: "4", name: "Hausverwaltung" },
  { id: "5", name: "Arbeitgeber" },
];

const documentTypes = [
  { id: "1", name: "Rechnung" },
  { id: "2", name: "Vertrag" },
  { id: "3", name: "Brief" },
  { id: "4", name: "Bescheid" },
  { id: "5", name: "Sonstiges" },
];

const ScanMetadataSheet = ({ isOpen, onClose, onUpload, pageCount }: ScanMetadataSheetProps) => {
  const [title, setTitle] = useState("");
  const [selectedTags, setSelectedTags] = useState<string[]>([]);
  const [selectedCorrespondent, setSelectedCorrespondent] = useState<string | null>(null);
  const [selectedDocType, setSelectedDocType] = useState<string | null>(null);
  const [showTagPicker, setShowTagPicker] = useState(false);
  const [showCorrespondentPicker, setShowCorrespondentPicker] = useState(false);
  const [showTypePicker, setShowTypePicker] = useState(false);

  const toggleTag = (tagId: string) => {
    setSelectedTags((prev) =>
      prev.includes(tagId)
        ? prev.filter((id) => id !== tagId)
        : [...prev, tagId]
    );
  };

  const selectedTagObjects = availableTags.filter((t) => selectedTags.includes(t.id));
  const correspondentName = correspondents.find((c) => c.id === selectedCorrespondent)?.name;
  const docTypeName = documentTypes.find((d) => d.id === selectedDocType)?.name;

  if (!isOpen) return null;

  return (
    <>
      {/* Backdrop */}
      <div 
        className="absolute inset-0 bg-background/60 backdrop-blur-md z-40 animate-fade-in"
        onClick={onClose}
      />

      {/* Sheet */}
      <div className="absolute bottom-0 left-0 right-0 z-50 max-h-[90%] flex flex-col animate-slide-up">
        <div className="bg-card rounded-t-[2rem] flex flex-col overflow-hidden shadow-elevated">
          {/* Handle */}
          <div className="flex justify-center pt-3 pb-1">
            <div className="w-10 h-1 rounded-full bg-muted-foreground/20" />
          </div>

          {/* Header */}
          <div className="px-6 pb-5 pt-3">
            <div className="flex items-start justify-between">
              <div className="flex items-center gap-4">
                {/* Icon card */}
                <div className="w-14 h-14 rounded-2xl pastel-cyan flex items-center justify-center">
                  <Sparkles className="w-7 h-7 text-foreground/70" />
                </div>
                <div>
                  <h2 className="text-2xl font-serif">Dokument speichern</h2>
                  <p className="text-sm text-muted-foreground mt-0.5">
                    {pageCount} Seite{pageCount > 1 ? "n" : ""} gescannt
                  </p>
                </div>
              </div>
              <button
                onClick={onClose}
                className="w-10 h-10 rounded-full bg-primary text-primary-foreground flex items-center justify-center"
              >
                <X className="w-5 h-5" />
              </button>
            </div>
          </div>

          {/* Content */}
          <div className="flex-1 overflow-y-auto px-6 py-2 space-y-5">
            {/* Title */}
            <div className="space-y-2">
              <label className="flex items-center gap-2 text-xs font-medium uppercase tracking-wider text-muted-foreground">
                <FileText className="w-3.5 h-3.5" />
                Titel
              </label>
              <input
                type="text"
                placeholder="Dokument_2024.pdf"
                value={title}
                onChange={(e) => setTitle(e.target.value)}
                className="w-full h-14 px-5 rounded-2xl bg-secondary border-0 text-base placeholder:text-muted-foreground/50 focus:outline-none focus:ring-2 focus:ring-primary/20 transition-all"
              />
            </div>

            {/* Tags */}
            <div className="space-y-2">
              <label className="flex items-center gap-2 text-xs font-medium uppercase tracking-wider text-muted-foreground">
                <Tag className="w-3.5 h-3.5" />
                Tags
              </label>
              <button
                onClick={() => setShowTagPicker(!showTagPicker)}
                className="w-full min-h-14 px-5 py-3 rounded-2xl bg-secondary text-left flex items-center justify-between transition-all active:scale-[0.99]"
              >
                {selectedTagObjects.length > 0 ? (
                  <div className="flex flex-wrap gap-2">
                    {selectedTagObjects.map((tag) => (
                      <span
                        key={tag.id}
                        className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-full text-sm font-medium"
                        style={{ backgroundColor: `${tag.color}20`, color: tag.color }}
                      >
                        <span
                          className="w-2 h-2 rounded-full"
                          style={{ backgroundColor: tag.color }}
                        />
                        {tag.name}
                      </span>
                    ))}
                  </div>
                ) : (
                  <span className="text-muted-foreground/60">Tags auswählen...</span>
                )}
                <ChevronDown className={`w-5 h-5 text-muted-foreground/50 transition-transform duration-300 ${showTagPicker ? "rotate-180" : ""}`} />
              </button>

              {/* Tag picker dropdown */}
              {showTagPicker && (
                <div className="rounded-2xl bg-card border border-border shadow-elevated overflow-hidden animate-scale-in">
                  <div className="p-3 grid grid-cols-2 gap-2">
                    {availableTags.map((tag) => {
                      const isSelected = selectedTags.includes(tag.id);
                      return (
                        <button
                          key={tag.id}
                          onClick={() => toggleTag(tag.id)}
                          className={`flex items-center gap-2.5 px-4 py-3 rounded-xl text-sm font-medium transition-all active:scale-[0.97] ${
                            isSelected 
                              ? "bg-primary/10 ring-2 ring-primary/30" 
                              : "bg-secondary hover:bg-muted"
                          }`}
                        >
                          <span
                            className="w-4 h-4 rounded-full shrink-0"
                            style={{ backgroundColor: tag.color }}
                          />
                          <span className="flex-1 text-left truncate">{tag.name}</span>
                          {isSelected && <Check className="w-4 h-4 text-primary shrink-0" />}
                        </button>
                      );
                    })}
                  </div>
                  <button className="w-full flex items-center justify-center gap-2 px-4 py-4 border-t border-border text-sm font-medium text-foreground hover:bg-secondary transition-colors">
                    <Plus className="w-4 h-4" />
                    Neuen Tag erstellen
                  </button>
                </div>
              )}
            </div>

            {/* Correspondent */}
            <div className="space-y-2">
              <label className="flex items-center gap-2 text-xs font-medium uppercase tracking-wider text-muted-foreground">
                <User className="w-3.5 h-3.5" />
                Korrespondent
              </label>
              <button
                onClick={() => setShowCorrespondentPicker(!showCorrespondentPicker)}
                className="w-full h-14 px-5 rounded-2xl bg-secondary text-left flex items-center justify-between transition-all active:scale-[0.99]"
              >
                <span className={correspondentName ? "text-foreground font-medium" : "text-muted-foreground/60"}>
                  {correspondentName || "Korrespondent auswählen..."}
                </span>
                <ChevronDown className={`w-5 h-5 text-muted-foreground/50 transition-transform duration-300 ${showCorrespondentPicker ? "rotate-180" : ""}`} />
              </button>

              {showCorrespondentPicker && (
                <div className="rounded-2xl bg-card border border-border shadow-elevated overflow-hidden max-h-52 overflow-y-auto animate-scale-in">
                  {correspondents.map((corr) => (
                    <button
                      key={corr.id}
                      onClick={() => {
                        setSelectedCorrespondent(corr.id);
                        setShowCorrespondentPicker(false);
                      }}
                      className={`w-full flex items-center gap-3 px-5 py-4 text-sm hover:bg-secondary transition-colors ${
                        selectedCorrespondent === corr.id ? "bg-primary/5" : ""
                      }`}
                    >
                      <div className="w-10 h-10 rounded-xl pastel-purple flex items-center justify-center shrink-0">
                        <User className="w-4 h-4 text-foreground/60" />
                      </div>
                      <span className="flex-1 text-left font-medium">{corr.name}</span>
                      {selectedCorrespondent === corr.id && (
                        <div className="w-6 h-6 rounded-full bg-primary flex items-center justify-center">
                          <Check className="w-3.5 h-3.5 text-primary-foreground" />
                        </div>
                      )}
                    </button>
                  ))}
                  <button className="w-full flex items-center justify-center gap-2 px-4 py-4 border-t border-border text-sm font-medium text-foreground hover:bg-secondary transition-colors">
                    <Plus className="w-4 h-4" />
                    Neuen Korrespondenten erstellen
                  </button>
                </div>
              )}
            </div>

            {/* Document Type */}
            <div className="space-y-2">
              <label className="flex items-center gap-2 text-xs font-medium uppercase tracking-wider text-muted-foreground">
                <Folder className="w-3.5 h-3.5" />
                Dokumenttyp
              </label>
              <button
                onClick={() => setShowTypePicker(!showTypePicker)}
                className="w-full h-14 px-5 rounded-2xl bg-secondary text-left flex items-center justify-between transition-all active:scale-[0.99]"
              >
                <span className={docTypeName ? "text-foreground font-medium" : "text-muted-foreground/60"}>
                  {docTypeName || "Dokumenttyp auswählen..."}
                </span>
                <ChevronDown className={`w-5 h-5 text-muted-foreground/50 transition-transform duration-300 ${showTypePicker ? "rotate-180" : ""}`} />
              </button>

              {showTypePicker && (
                <div className="rounded-2xl bg-card border border-border shadow-elevated overflow-hidden animate-scale-in">
                  {documentTypes.map((type) => (
                    <button
                      key={type.id}
                      onClick={() => {
                        setSelectedDocType(type.id);
                        setShowTypePicker(false);
                      }}
                      className={`w-full flex items-center gap-3 px-5 py-4 text-sm hover:bg-secondary transition-colors ${
                        selectedDocType === type.id ? "bg-primary/5" : ""
                      }`}
                    >
                      <div className="w-10 h-10 rounded-xl pastel-yellow flex items-center justify-center">
                        <Folder className="w-4 h-4 text-foreground/60" />
                      </div>
                      <span className="flex-1 text-left font-medium">{type.name}</span>
                      {selectedDocType === type.id && (
                        <div className="w-6 h-6 rounded-full bg-primary flex items-center justify-center">
                          <Check className="w-3.5 h-3.5 text-primary-foreground" />
                        </div>
                      )}
                    </button>
                  ))}
                </div>
              )}
            </div>
          </div>

          {/* Actions */}
          <div className="p-6 pt-4 space-y-3">
            <Button size="lg" className="w-full h-14 text-base font-semibold rounded-full" onClick={onUpload}>
              <Upload className="w-5 h-5 mr-2" />
              Jetzt hochladen
            </Button>
            <button 
              className="w-full py-3 text-sm font-medium text-muted-foreground hover:text-foreground transition-colors"
              onClick={onClose}
            >
              Später bearbeiten
            </button>
          </div>
        </div>
      </div>
    </>
  );
};

export default ScanMetadataSheet;
