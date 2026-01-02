import { useState } from "react";
import { Tag, Plus, Search, ArrowLeft, FileText, X, Trash2, Edit2 } from "lucide-react";
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";

interface Label {
  id: string;
  name: string;
  color: string;
  count: number;
}

interface Document {
  id: string;
  title: string;
  date: string;
  pages: number;
}

const colorOptions = [
  { name: "Türkis", value: "hsl(174 72% 46%)" },
  { name: "Orange", value: "hsl(35 92% 55%)" },
  { name: "Lila", value: "hsl(280 70% 50%)" },
  { name: "Blau", value: "hsl(200 70% 50%)" },
  { name: "Grün", value: "hsl(120 60% 45%)" },
  { name: "Rot", value: "hsl(0 70% 55%)" },
  { name: "Pink", value: "hsl(330 70% 55%)" },
  { name: "Gelb", value: "hsl(50 90% 50%)" },
];

const initialLabels: Label[] = [
  { id: "1", name: "Rechnung", color: "hsl(174 72% 46%)", count: 24 },
  { id: "2", name: "Vertrag", color: "hsl(35 92% 55%)", count: 8 },
  { id: "3", name: "Steuer", color: "hsl(280 70% 50%)", count: 15 },
  { id: "4", name: "Versicherung", color: "hsl(200 70% 50%)", count: 12 },
  { id: "5", name: "Gehalt", color: "hsl(120 60% 45%)", count: 36 },
  { id: "6", name: "Medizin", color: "hsl(0 70% 55%)", count: 7 },
];

const mockDocumentsByLabel: Record<string, Document[]> = {
  "1": [
    { id: "d1", title: "Stromrechnung März", date: "15.03.2024", pages: 2 },
    { id: "d2", title: "Wasserrechnung Q1", date: "10.03.2024", pages: 1 },
    { id: "d3", title: "Telefonrechnung", date: "01.03.2024", pages: 3 },
  ],
  "2": [
    { id: "d4", title: "Mietvertrag", date: "01.01.2023", pages: 12 },
    { id: "d5", title: "Arbeitsvertrag", date: "15.06.2022", pages: 8 },
  ],
  "3": [
    { id: "d6", title: "Steuerbescheid 2023", date: "20.04.2024", pages: 4 },
    { id: "d7", title: "Lohnsteuerbescheinigung", date: "15.02.2024", pages: 1 },
  ],
  "4": [
    { id: "d8", title: "KFZ-Versicherung", date: "01.01.2024", pages: 6 },
    { id: "d9", title: "Hausratversicherung", date: "15.12.2023", pages: 4 },
  ],
  "5": [
    { id: "d10", title: "Gehaltsabrechnung März", date: "31.03.2024", pages: 1 },
    { id: "d11", title: "Gehaltsabrechnung Feb", date: "28.02.2024", pages: 1 },
  ],
  "6": [
    { id: "d12", title: "Arztrechnung", date: "10.03.2024", pages: 1 },
    { id: "d13", title: "Rezept", date: "05.03.2024", pages: 1 },
  ],
};

const LabelsScreen = () => {
  const [labels, setLabels] = useState<Label[]>(initialLabels);
  const [searchQuery, setSearchQuery] = useState("");
  const [selectedLabel, setSelectedLabel] = useState<Label | null>(null);
  const [isDialogOpen, setIsDialogOpen] = useState(false);
  const [editingLabel, setEditingLabel] = useState<Label | null>(null);
  const [newLabelName, setNewLabelName] = useState("");
  const [newLabelColor, setNewLabelColor] = useState(colorOptions[0].value);

  const filteredLabels = labels.filter((label) =>
    label.name.toLowerCase().includes(searchQuery.toLowerCase())
  );

  const documentsForLabel = selectedLabel
    ? mockDocumentsByLabel[selectedLabel.id] || []
    : [];

  const openCreateDialog = () => {
    setEditingLabel(null);
    setNewLabelName("");
    setNewLabelColor(colorOptions[0].value);
    setIsDialogOpen(true);
  };

  const openEditDialog = (label: Label, e: React.MouseEvent) => {
    e.stopPropagation();
    setEditingLabel(label);
    setNewLabelName(label.name);
    setNewLabelColor(label.color);
    setIsDialogOpen(true);
  };

  const handleSaveLabel = () => {
    if (!newLabelName.trim()) return;

    if (editingLabel) {
      // Update existing label
      setLabels(labels.map(l => 
        l.id === editingLabel.id 
          ? { ...l, name: newLabelName.trim(), color: newLabelColor }
          : l
      ));
    } else {
      // Create new label
      const newLabel: Label = {
        id: Date.now().toString(),
        name: newLabelName.trim(),
        color: newLabelColor,
        count: 0,
      };
      setLabels([...labels, newLabel]);
    }
    setIsDialogOpen(false);
  };

  const handleDeleteLabel = (labelId: string, e: React.MouseEvent) => {
    e.stopPropagation();
    setLabels(labels.filter(l => l.id !== labelId));
  };

  // Document list view for selected label
  if (selectedLabel) {
    return (
      <div className="flex flex-col h-full bg-background">
        {/* Header */}
        <div className="px-5 pt-6 pb-4">
          <button
            onClick={() => setSelectedLabel(null)}
            className="flex items-center gap-2 text-muted-foreground mb-4"
          >
            <ArrowLeft className="w-5 h-5" />
            <span className="text-sm">Zurück</span>
          </button>

          <div className="flex items-center gap-3">
            <div
              className="w-4 h-4 rounded-full"
              style={{ backgroundColor: selectedLabel.color }}
            />
            <h1 className="font-serif text-2xl font-semibold text-foreground">
              {selectedLabel.name}
            </h1>
          </div>
          <p className="text-muted-foreground text-sm mt-1">
            {documentsForLabel.length} Dokumente
          </p>
        </div>

        {/* Document List */}
        <div className="flex-1 overflow-y-auto px-5 pb-6">
          <div className="space-y-3">
            {documentsForLabel.length === 0 ? (
              <div className="text-center py-12">
                <FileText className="w-12 h-12 text-muted-foreground/50 mx-auto mb-3" />
                <p className="text-muted-foreground">Keine Dokumente mit diesem Label</p>
              </div>
            ) : (
              documentsForLabel.map((doc) => (
                <div
                  key={doc.id}
                  className="bg-card rounded-2xl p-4 border border-border/50 active:scale-[0.98] transition-transform cursor-pointer"
                >
                  <div className="flex items-start gap-3">
                    <div className="w-10 h-10 rounded-xl bg-secondary flex items-center justify-center">
                      <FileText className="w-5 h-5 text-muted-foreground" />
                    </div>
                    <div className="flex-1 min-w-0">
                      <h3 className="font-medium text-foreground truncate">
                        {doc.title}
                      </h3>
                      <div className="flex items-center gap-2 mt-1">
                        <span className="text-xs text-muted-foreground">
                          {doc.date}
                        </span>
                        <span className="text-xs text-muted-foreground">•</span>
                        <span className="text-xs text-muted-foreground">
                          {doc.pages} {doc.pages === 1 ? "Seite" : "Seiten"}
                        </span>
                      </div>
                    </div>
                  </div>
                </div>
              ))
            )}
          </div>
        </div>
      </div>
    );
  }

  // Labels overview
  return (
    <div className="flex flex-col h-full bg-background">
      {/* Header */}
      <div className="px-5 pt-6 pb-4">
        <h1 className="font-serif text-2xl font-semibold text-foreground">
          Labels
        </h1>
        <p className="text-muted-foreground text-sm mt-1">
          Verwalte deine Labels
        </p>
      </div>

      {/* Search */}
      <div className="px-5 pb-4">
        <div className="relative">
          <Search className="absolute left-4 top-1/2 -translate-y-1/2 w-5 h-5 text-muted-foreground" />
          <input
            type="text"
            placeholder="Labels durchsuchen..."
            value={searchQuery}
            onChange={(e) => setSearchQuery(e.target.value)}
            className="w-full h-12 pl-12 pr-4 rounded-2xl bg-card border border-border/50 text-sm placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-primary/30"
          />
        </div>
      </div>

      {/* Labels Grid */}
      <div className="flex-1 overflow-y-auto px-5 pb-6">
        <div className="grid grid-cols-2 gap-3">
          {filteredLabels.map((label) => (
            <div
              key={label.id}
              onClick={() => setSelectedLabel(label)}
              className="bg-card rounded-2xl p-4 border border-border/50 text-left active:scale-[0.98] transition-transform cursor-pointer relative group"
            >
              {/* Action buttons */}
              <div className="absolute top-2 right-2 flex gap-1 opacity-0 group-hover:opacity-100 transition-opacity">
                <button
                  onClick={(e) => openEditDialog(label, e)}
                  className="w-7 h-7 rounded-lg bg-secondary/80 flex items-center justify-center hover:bg-secondary"
                >
                  <Edit2 className="w-3.5 h-3.5 text-muted-foreground" />
                </button>
                <button
                  onClick={(e) => handleDeleteLabel(label.id, e)}
                  className="w-7 h-7 rounded-lg bg-destructive/10 flex items-center justify-center hover:bg-destructive/20"
                >
                  <Trash2 className="w-3.5 h-3.5 text-destructive" />
                </button>
              </div>

              <div className="flex items-center gap-2 mb-2">
                <div
                  className="w-3 h-3 rounded-full"
                  style={{ backgroundColor: label.color }}
                />
                <span className="font-medium text-foreground truncate">
                  {label.name}
                </span>
              </div>
              <p className="text-sm text-muted-foreground">
                {label.count} Dokumente
              </p>
            </div>
          ))}

          {/* Add new label */}
          <button
            onClick={openCreateDialog}
            className="bg-card rounded-2xl p-4 border-2 border-dashed border-border hover:border-primary/50 transition-colors flex flex-col items-center justify-center gap-2 text-muted-foreground hover:text-foreground"
          >
            <Plus className="w-6 h-6" />
            <span className="text-sm font-medium">Neues Label</span>
          </button>
        </div>
      </div>

      {/* Create/Edit Dialog */}
      <Dialog open={isDialogOpen} onOpenChange={setIsDialogOpen}>
        <DialogContent className="max-w-[340px] rounded-2xl">
          <DialogHeader>
            <DialogTitle className="font-serif text-xl">
              {editingLabel ? "Label bearbeiten" : "Neues Label"}
            </DialogTitle>
          </DialogHeader>

          <div className="space-y-5 pt-2">
            {/* Name input */}
            <div className="space-y-2">
              <label className="text-sm font-medium text-foreground">Name</label>
              <input
                type="text"
                value={newLabelName}
                onChange={(e) => setNewLabelName(e.target.value)}
                placeholder="Label-Name eingeben..."
                className="w-full h-12 px-4 rounded-xl bg-secondary border border-border/50 text-sm placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-primary/30"
                autoFocus
              />
            </div>

            {/* Color picker */}
            <div className="space-y-2">
              <label className="text-sm font-medium text-foreground">Farbe</label>
              <div className="grid grid-cols-4 gap-2">
                {colorOptions.map((color) => (
                  <button
                    key={color.value}
                    onClick={() => setNewLabelColor(color.value)}
                    className={`w-full aspect-square rounded-xl transition-all ${
                      newLabelColor === color.value
                        ? "ring-2 ring-primary ring-offset-2 ring-offset-background scale-110"
                        : "hover:scale-105"
                    }`}
                    style={{ backgroundColor: color.value }}
                    title={color.name}
                  />
                ))}
              </div>
            </div>

            {/* Preview */}
            <div className="space-y-2">
              <label className="text-sm font-medium text-foreground">Vorschau</label>
              <div className="bg-card rounded-xl p-3 border border-border/50 flex items-center gap-2">
                <div
                  className="w-3 h-3 rounded-full"
                  style={{ backgroundColor: newLabelColor }}
                />
                <span className="font-medium text-foreground">
                  {newLabelName || "Label-Name"}
                </span>
              </div>
            </div>

            {/* Actions */}
            <div className="flex gap-3 pt-2">
              <button
                onClick={() => setIsDialogOpen(false)}
                className="flex-1 h-12 rounded-xl bg-secondary text-foreground font-medium hover:bg-secondary/80 transition-colors"
              >
                Abbrechen
              </button>
              <button
                onClick={handleSaveLabel}
                disabled={!newLabelName.trim()}
                className="flex-1 h-12 rounded-xl bg-primary text-primary-foreground font-medium hover:bg-primary/90 transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
              >
                {editingLabel ? "Speichern" : "Erstellen"}
              </button>
            </div>
          </div>
        </DialogContent>
      </Dialog>
    </div>
  );
};

export default LabelsScreen;
