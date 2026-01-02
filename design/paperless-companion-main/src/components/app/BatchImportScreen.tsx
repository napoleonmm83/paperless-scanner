import { useState } from "react";
import { 
  Image, 
  X, 
  Check, 
  Upload, 
  ChevronLeft, 
  Plus,
  FileText,
  Trash2,
  GripVertical,
  AlertCircle
} from "lucide-react";
import { Button } from "@/components/ui/button";
import { Progress } from "@/components/ui/progress";

interface ImportImage {
  id: string;
  name: string;
  size: string;
  thumbnail: string;
  status: "pending" | "uploading" | "success" | "error";
  progress: number;
}

interface BatchImportScreenProps {
  onClose: () => void;
}

const mockImages: ImportImage[] = [
  { id: "1", name: "IMG_2024_001.jpg", size: "2.4 MB", thumbnail: "", status: "success", progress: 100 },
  { id: "2", name: "IMG_2024_002.jpg", size: "1.8 MB", thumbnail: "", status: "uploading", progress: 65 },
  { id: "3", name: "IMG_2024_003.jpg", size: "3.1 MB", thumbnail: "", status: "pending", progress: 0 },
  { id: "4", name: "Scan_Rechnung.jpg", size: "1.2 MB", thumbnail: "", status: "pending", progress: 0 },
  { id: "5", name: "Dokument_neu.jpg", size: "2.8 MB", thumbnail: "", status: "pending", progress: 0 },
];

const BatchImportScreen = ({ onClose }: BatchImportScreenProps) => {
  const [images, setImages] = useState<ImportImage[]>(mockImages);
  const [isUploading, setIsUploading] = useState(true);

  const completedCount = images.filter((img) => img.status === "success").length;
  const totalProgress = Math.round(
    images.reduce((acc, img) => acc + img.progress, 0) / images.length
  );

  const removeImage = (id: string) => {
    setImages(images.filter((img) => img.id !== id));
  };

  const getStatusIcon = (status: ImportImage["status"], progress: number) => {
    switch (status) {
      case "success":
        return (
          <div className="w-8 h-8 rounded-full bg-primary/20 flex items-center justify-center">
            <Check className="w-4 h-4 text-primary" />
          </div>
        );
      case "uploading":
        return (
          <div className="relative w-8 h-8">
            <svg className="w-8 h-8 -rotate-90">
              <circle
                cx="16"
                cy="16"
                r="14"
                fill="none"
                stroke="hsl(var(--secondary))"
                strokeWidth="3"
              />
              <circle
                cx="16"
                cy="16"
                r="14"
                fill="none"
                stroke="hsl(var(--primary))"
                strokeWidth="3"
                strokeDasharray={`${progress * 0.88} 88`}
                strokeLinecap="round"
              />
            </svg>
            <span className="absolute inset-0 flex items-center justify-center text-[10px] font-medium text-primary">
              {progress}%
            </span>
          </div>
        );
      case "error":
        return (
          <div className="w-8 h-8 rounded-full bg-destructive/20 flex items-center justify-center">
            <AlertCircle className="w-4 h-4 text-destructive" />
          </div>
        );
      default:
        return (
          <div className="w-8 h-8 rounded-full bg-secondary flex items-center justify-center">
            <Upload className="w-4 h-4 text-muted-foreground" />
          </div>
        );
    }
  };

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
          <h1 className="text-lg font-semibold">Batch Import</h1>
          <p className="text-sm text-muted-foreground">
            {images.length} Bilder ausgewählt
          </p>
        </div>
        <Button variant="ghost" size="sm" className="text-destructive">
          <Trash2 className="w-4 h-4 mr-1" />
          Alle
        </Button>
      </div>

      {/* Progress overview */}
      {isUploading && (
        <div className="px-4 py-4 bg-card border-b border-border space-y-3">
          <div className="flex items-center justify-between">
            <span className="text-sm font-medium">Upload-Fortschritt</span>
            <span className="text-sm text-muted-foreground">
              {completedCount} von {images.length} fertig
            </span>
          </div>
          <Progress value={totalProgress} className="h-2" />
          <div className="flex items-center gap-2 text-xs text-muted-foreground">
            <div className="w-2 h-2 rounded-full bg-primary animate-pulse" />
            Hochladen im Hintergrund aktiv
          </div>
        </div>
      )}

      {/* Image list */}
      <div className="flex-1 overflow-y-auto px-4 py-4 space-y-2">
        {images.map((image, index) => (
          <div
            key={image.id}
            className="glass-card p-3 flex items-center gap-3"
          >
            {/* Drag handle */}
            <button className="w-8 h-10 flex items-center justify-center text-muted-foreground hover:text-foreground cursor-grab active:cursor-grabbing">
              <GripVertical className="w-5 h-5" />
            </button>

            {/* Thumbnail */}
            <div className="w-14 h-14 rounded-lg bg-gradient-to-br from-secondary to-muted overflow-hidden shrink-0 relative">
              <div className="absolute inset-0 flex items-center justify-center">
                <Image className="w-6 h-6 text-muted-foreground/50" />
              </div>
              <span className="absolute bottom-1 right-1 text-[10px] bg-background/80 px-1 rounded font-medium">
                {index + 1}
              </span>
            </div>

            {/* Info */}
            <div className="flex-1 min-w-0">
              <p className="font-medium truncate text-sm">{image.name}</p>
              <p className="text-xs text-muted-foreground">{image.size}</p>
              {image.status === "uploading" && (
                <div className="mt-1.5">
                  <Progress value={image.progress} className="h-1" />
                </div>
              )}
            </div>

            {/* Status */}
            {getStatusIcon(image.status, image.progress)}

            {/* Remove button */}
            <button
              onClick={() => removeImage(image.id)}
              className="w-8 h-8 rounded-lg hover:bg-destructive/10 flex items-center justify-center text-muted-foreground hover:text-destructive transition-colors"
            >
              <X className="w-4 h-4" />
            </button>
          </div>
        ))}

        {/* Add more images */}
        <button className="w-full glass-card p-4 border-2 border-dashed border-border hover:border-primary/50 transition-colors flex items-center justify-center gap-3 text-muted-foreground hover:text-foreground">
          <Plus className="w-5 h-5" />
          <span className="font-medium">Weitere Bilder hinzufügen</span>
        </button>
      </div>

      {/* Bottom actions */}
      <div className="p-4 bg-card border-t border-border space-y-3">
        {/* Metadata hint */}
        <div className="flex items-start gap-3 p-3 rounded-xl bg-primary/5 border border-primary/20">
          <FileText className="w-5 h-5 text-primary shrink-0 mt-0.5" />
          <div className="text-sm">
            <p className="font-medium text-primary">Tipp</p>
            <p className="text-muted-foreground">
              Du kannst Tags und Korrespondenten nach dem Upload in Paperless-ngx zuweisen.
            </p>
          </div>
        </div>

        <div className="flex gap-3">
          <Button variant="outline" className="flex-1" onClick={onClose}>
            Abbrechen
          </Button>
          <Button className="flex-1" disabled={images.length === 0}>
            <Upload className="w-4 h-4 mr-2" />
            {isUploading ? "Läuft..." : "Hochladen"}
          </Button>
        </div>
      </div>
    </div>
  );
};

export default BatchImportScreen;
