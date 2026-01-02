import { useState } from "react";
import { 
  Image, 
  Check,
  X,
  ChevronLeft,
  FolderOpen,
  Clock,
  Camera
} from "lucide-react";
import { Button } from "@/components/ui/button";

interface GalleryImage {
  id: string;
  name: string;
  date: string;
  selected: boolean;
}

interface GalleryPickerProps {
  onClose: () => void;
  onConfirm: (selectedIds: string[]) => void;
}

const mockGalleryImages: GalleryImage[] = Array.from({ length: 20 }, (_, i) => ({
  id: `img-${i + 1}`,
  name: `IMG_2024_${String(i + 1).padStart(3, "0")}.jpg`,
  date: i < 5 ? "Heute" : i < 10 ? "Gestern" : "Letzte Woche",
  selected: false,
}));

const GalleryPicker = ({ onClose, onConfirm }: GalleryPickerProps) => {
  const [images, setImages] = useState(mockGalleryImages);
  const [activeFolder, setActiveFolder] = useState<"recent" | "camera" | "all">("recent");

  const selectedCount = images.filter((img) => img.selected).length;

  const toggleSelect = (id: string) => {
    setImages(
      images.map((img) =>
        img.id === id ? { ...img, selected: !img.selected } : img
      )
    );
  };

  const selectAll = () => {
    const allSelected = images.every((img) => img.selected);
    setImages(images.map((img) => ({ ...img, selected: !allSelected })));
  };

  const handleConfirm = () => {
    const selectedIds = images.filter((img) => img.selected).map((img) => img.id);
    onConfirm(selectedIds);
  };

  const folders = [
    { id: "recent" as const, label: "Zuletzt", icon: Clock },
    { id: "camera" as const, label: "Kamera", icon: Camera },
    { id: "all" as const, label: "Alle", icon: FolderOpen },
  ];

  return (
    <div className="flex flex-col h-full bg-background">
      {/* Header */}
      <div className="flex items-center gap-3 px-4 py-3 bg-card border-b border-border">
        <button
          onClick={onClose}
          className="w-10 h-10 rounded-xl hover:bg-secondary flex items-center justify-center"
        >
          <ChevronLeft className="w-6 h-6" />
        </button>
        <div className="flex-1">
          <h1 className="text-lg font-semibold">Galerie</h1>
          <p className="text-sm text-muted-foreground">
            {selectedCount > 0
              ? `${selectedCount} ausgewählt`
              : "Bilder auswählen"}
          </p>
        </div>
        <button
          onClick={selectAll}
          className="px-3 py-1.5 rounded-lg bg-secondary text-sm font-medium hover:bg-secondary/80 transition-colors"
        >
          {images.every((img) => img.selected) ? "Keine" : "Alle"}
        </button>
      </div>

      {/* Folder tabs */}
      <div className="flex gap-2 px-4 py-3 bg-card border-b border-border overflow-x-auto">
        {folders.map((folder) => (
          <button
            key={folder.id}
            onClick={() => setActiveFolder(folder.id)}
            className={`flex items-center gap-2 px-4 py-2 rounded-lg text-sm font-medium whitespace-nowrap transition-all ${
              activeFolder === folder.id
                ? "bg-primary text-primary-foreground"
                : "bg-secondary text-muted-foreground hover:text-foreground"
            }`}
          >
            <folder.icon className="w-4 h-4" />
            {folder.label}
          </button>
        ))}
      </div>

      {/* Image grid */}
      <div className="flex-1 overflow-y-auto p-2">
        <div className="grid grid-cols-3 gap-1">
          {images.map((image, index) => (
            <button
              key={image.id}
              onClick={() => toggleSelect(image.id)}
              className={`relative aspect-square rounded-lg overflow-hidden group transition-all ${
                image.selected ? "ring-2 ring-primary ring-offset-2 ring-offset-background" : ""
              }`}
            >
              {/* Placeholder image */}
              <div className="absolute inset-0 bg-gradient-to-br from-secondary via-muted to-secondary">
                <div className="absolute inset-0 flex items-center justify-center">
                  <Image className="w-8 h-8 text-muted-foreground/30" />
                </div>
              </div>

              {/* Selection overlay */}
              <div
                className={`absolute inset-0 transition-all ${
                  image.selected
                    ? "bg-primary/20"
                    : "bg-transparent group-hover:bg-foreground/10"
                }`}
              />

              {/* Checkbox */}
              <div
                className={`absolute top-2 right-2 w-6 h-6 rounded-full border-2 flex items-center justify-center transition-all ${
                  image.selected
                    ? "bg-primary border-primary"
                    : "border-foreground/50 bg-background/50 backdrop-blur-sm"
                }`}
              >
                {image.selected && <Check className="w-4 h-4 text-primary-foreground" />}
              </div>

              {/* Index when selected */}
              {image.selected && (
                <div className="absolute bottom-2 left-2 px-2 py-0.5 rounded bg-primary text-primary-foreground text-xs font-medium">
                  {images.filter((img) => img.selected).findIndex((img) => img.id === image.id) + 1}
                </div>
              )}
            </button>
          ))}
        </div>
      </div>

      {/* Bottom actions */}
      <div className="p-4 bg-card border-t border-border">
        <div className="flex gap-3">
          <Button variant="outline" className="flex-1" onClick={onClose}>
            Abbrechen
          </Button>
          <Button
            className="flex-1"
            onClick={handleConfirm}
            disabled={selectedCount === 0}
          >
            {selectedCount > 0 ? `${selectedCount} importieren` : "Auswählen"}
          </Button>
        </div>
      </div>
    </div>
  );
};

export default GalleryPicker;
