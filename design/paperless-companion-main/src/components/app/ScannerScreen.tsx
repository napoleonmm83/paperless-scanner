import { useState } from "react";
import { 
  Camera, 
  Upload, 
  Image, 
  FileText, 
  FolderOpen,
  Zap,
  FlipHorizontal,
  X,
  Check,
  ArrowLeft
} from "lucide-react";
import ScanMetadataSheet from "./ScanMetadataSheet";
import { toast } from "sonner";

type ScanMode = "select" | "camera" | "gallery";

const ScannerScreen = () => {
  const [mode, setMode] = useState<ScanMode>("select");
  const [flashOn, setFlashOn] = useState(false);
  const [pageCount, setPageCount] = useState(0);
  const [showMetadataSheet, setShowMetadataSheet] = useState(false);

  const handleCapture = () => {
    setPageCount((prev) => prev + 1);
  };

  const handleFinish = () => {
    if (pageCount > 0) {
      setShowMetadataSheet(true);
    }
  };

  const handleUpload = () => {
    setShowMetadataSheet(false);
    toast.success("Dokument wird hochgeladen", {
      description: `${pageCount} Seite${pageCount > 1 ? "n" : ""} werden an Paperless-ngx gesendet`
    });
    setPageCount(0);
    setMode("select");
  };

  const handleGallerySelect = () => {
    // Simulate selecting images
    setPageCount(3);
    setShowMetadataSheet(true);
  };

  const removePage = (index: number) => {
    setPageCount((prev) => Math.max(0, prev - 1));
  };

  // Mode selection screen
  if (mode === "select") {
    return (
      <div className="flex flex-col h-full bg-background">
        {/* Header */}
        <div className="px-6 pt-6 pb-4">
          <h1 className="text-3xl font-serif">Neues Dokument</h1>
          <p className="text-sm text-muted-foreground mt-1">Wähle eine Option</p>
        </div>

        {/* Options Row */}
        <div className="flex-1 px-6 flex items-center">
          <div className="flex gap-4 w-full">
            {/* Scan option */}
            <button
              onClick={() => setMode("camera")}
              className="flex-1 pastel-cyan rounded-3xl p-5 flex flex-col items-center justify-center gap-3 min-h-[160px] active:scale-[0.97] transition-transform"
            >
              <div className="w-14 h-14 rounded-2xl bg-card/80 border border-border/50 flex items-center justify-center">
                <Camera className="w-6 h-6 text-foreground/70" />
              </div>
              <span className="text-sm font-semibold text-foreground/90">Scannen</span>
            </button>

            {/* Gallery option */}
            <button
              onClick={() => setMode("gallery")}
              className="flex-1 pastel-yellow rounded-3xl p-5 flex flex-col items-center justify-center gap-3 min-h-[160px] active:scale-[0.97] transition-transform"
            >
              <div className="w-14 h-14 rounded-2xl bg-card/80 border border-border/50 flex items-center justify-center">
                <Image className="w-6 h-6 text-foreground/70" />
              </div>
              <span className="text-sm font-semibold text-foreground/90">Galerie</span>
            </button>

            {/* Files option */}
            <button
              onClick={handleGallerySelect}
              className="flex-1 pastel-purple rounded-3xl p-5 flex flex-col items-center justify-center gap-3 min-h-[160px] active:scale-[0.97] transition-transform"
            >
              <div className="w-14 h-14 rounded-2xl bg-card/80 border border-border/50 flex items-center justify-center">
                <FolderOpen className="w-6 h-6 text-foreground/70" />
              </div>
              <span className="text-sm font-semibold text-foreground/90">Dateien</span>
            </button>
          </div>
        </div>

        {/* Metadata Sheet */}
        <ScanMetadataSheet
          isOpen={showMetadataSheet}
          onClose={() => setShowMetadataSheet(false)}
          onUpload={handleUpload}
          pageCount={pageCount}
        />
      </div>
    );
  }

  // Gallery mode
  if (mode === "gallery") {
    return (
      <div className="flex flex-col h-full bg-background">
        {/* Header */}
        <div className="flex items-center gap-4 px-6 py-4">
          <button
            onClick={() => setMode("select")}
            className="w-12 h-12 rounded-2xl bg-card border border-border flex items-center justify-center"
          >
            <ArrowLeft className="w-5 h-5 text-foreground/60" />
          </button>
          <div>
            <h1 className="text-2xl font-serif">Galerie</h1>
            <p className="text-sm text-muted-foreground">Bilder auswählen</p>
          </div>
        </div>

        {/* Mock gallery grid */}
        <div className="flex-1 px-6 pb-6 overflow-y-auto">
          <div className="grid grid-cols-3 gap-2">
            {Array.from({ length: 12 }).map((_, i) => (
              <button
                key={i}
                className="aspect-square rounded-xl bg-secondary border border-border overflow-hidden relative group"
              >
                <div className="absolute inset-0 bg-gradient-to-br from-muted to-secondary" />
                <div className="absolute inset-0 bg-primary/0 group-hover:bg-primary/10 transition-colors" />
                <div className="absolute top-2 right-2 w-6 h-6 rounded-full bg-card/80 border border-border flex items-center justify-center opacity-0 group-hover:opacity-100 transition-opacity">
                  <Check className="w-3.5 h-3.5 text-primary" />
                </div>
              </button>
            ))}
          </div>
        </div>

        {/* Bottom action */}
        <div className="p-6 bg-card border-t border-border">
          <button
            onClick={handleGallerySelect}
            className="w-full h-14 rounded-full bg-primary text-primary-foreground font-semibold flex items-center justify-center gap-2 active:scale-[0.98] transition-transform"
          >
            <Check className="w-5 h-5" />
            Auswahl bestätigen
          </button>
        </div>

        {/* Metadata Sheet */}
        <ScanMetadataSheet
          isOpen={showMetadataSheet}
          onClose={() => setShowMetadataSheet(false)}
          onUpload={handleUpload}
          pageCount={pageCount}
        />
      </div>
    );
  }

  // Camera mode
  return (
    <div className="flex flex-col h-full relative bg-background">
      {/* Header */}
      <div className="flex items-center gap-4 px-6 py-4">
        <button
          onClick={() => {
            setMode("select");
            setPageCount(0);
          }}
          className="w-12 h-12 rounded-2xl bg-card border border-border flex items-center justify-center"
        >
          <ArrowLeft className="w-5 h-5 text-foreground/60" />
        </button>
        <div className="flex-1">
          <h1 className="text-2xl font-serif">Scannen</h1>
          {pageCount > 0 && (
            <p className="text-sm text-muted-foreground">
              {pageCount} Seite{pageCount > 1 ? "n" : ""} erfasst
            </p>
          )}
        </div>
        {pageCount > 0 && (
          <div className="px-4 py-2 rounded-full bg-pastel-green">
            <span className="text-sm font-medium">{pageCount}</span>
          </div>
        )}
      </div>

      {/* Camera preview */}
      <div className="flex-1 mx-6 mb-4 relative rounded-3xl overflow-hidden bg-muted">
        <div className="absolute inset-0 bg-gradient-to-br from-secondary via-muted to-secondary">
          {/* Grid overlay */}
          <div className="absolute inset-0 opacity-10">
            <div className="absolute left-1/3 top-0 bottom-0 w-px bg-foreground" />
            <div className="absolute right-1/3 top-0 bottom-0 w-px bg-foreground" />
            <div className="absolute top-1/3 left-0 right-0 h-px bg-foreground" />
            <div className="absolute bottom-1/3 left-0 right-0 h-px bg-foreground" />
          </div>

          {/* Document detection frame */}
          <div className="absolute inset-6 border-2 border-primary/60 rounded-2xl">
            <div className="absolute -top-0.5 -left-0.5 w-8 h-8 border-t-4 border-l-4 border-primary rounded-tl-xl" />
            <div className="absolute -top-0.5 -right-0.5 w-8 h-8 border-t-4 border-r-4 border-primary rounded-tr-xl" />
            <div className="absolute -bottom-0.5 -left-0.5 w-8 h-8 border-b-4 border-l-4 border-primary rounded-bl-xl" />
            <div className="absolute -bottom-0.5 -right-0.5 w-8 h-8 border-b-4 border-r-4 border-primary rounded-br-xl" />
            <div className="absolute left-2 right-2 h-0.5 bg-gradient-to-r from-transparent via-primary to-transparent animate-[scan_2s_ease-in-out_infinite]" />
          </div>

          {/* Detection status */}
          <div className="absolute bottom-4 left-1/2 -translate-x-1/2 flex items-center gap-2 px-4 py-2.5 rounded-full bg-card/95 backdrop-blur-sm border border-border">
            <div className="w-2 h-2 rounded-full bg-primary animate-pulse" />
            <span className="text-sm font-medium">Dokument erkannt</span>
          </div>
        </div>

        {/* Top controls */}
        <div className="absolute top-4 left-4 right-4 flex items-center justify-between">
          <button
            onClick={() => setFlashOn(!flashOn)}
            className={`w-12 h-12 rounded-2xl flex items-center justify-center transition-all ${
              flashOn
                ? "bg-pastel-yellow"
                : "bg-card/90 backdrop-blur-sm border border-border"
            }`}
          >
            <Zap className={`w-5 h-5 ${flashOn ? "fill-current text-foreground/80" : "text-foreground/60"}`} />
          </button>

          <button className="w-12 h-12 rounded-2xl bg-card/90 backdrop-blur-sm border border-border flex items-center justify-center">
            <FlipHorizontal className="w-5 h-5 text-foreground/60" />
          </button>
        </div>
      </div>

      {/* Bottom controls */}
      <div className="p-6 bg-card rounded-t-3xl border-t border-border">
        <div className="flex items-center justify-between">
          {/* Gallery shortcut */}
          <button 
            onClick={() => setMode("gallery")}
            className="w-14 h-14 rounded-2xl bg-secondary flex items-center justify-center active:scale-95 transition-transform"
          >
            <Image className="w-6 h-6 text-foreground/60" />
          </button>

          {/* Capture button */}
          <button
            onClick={handleCapture}
            className="w-20 h-20 rounded-full bg-primary flex items-center justify-center shadow-elevated active:scale-95 transition-transform"
          >
            <div className="w-16 h-16 rounded-full border-4 border-primary-foreground flex items-center justify-center">
              <Camera className="w-7 h-7 text-primary-foreground" />
            </div>
          </button>

          {/* Finish button */}
          {pageCount > 0 ? (
            <button
              onClick={handleFinish}
              className="w-14 h-14 rounded-2xl bg-pastel-green flex items-center justify-center active:scale-95 transition-transform"
            >
              <Check className="w-6 h-6 text-foreground/70" />
            </button>
          ) : (
            <div className="w-14 h-14" />
          )}
        </div>

        {/* Page thumbnails */}
        {pageCount > 0 && (
          <div className="mt-5 flex items-center gap-3 overflow-x-auto pb-2">
            {Array.from({ length: pageCount }).map((_, i) => (
              <div
                key={i}
                className="relative w-14 h-18 rounded-xl bg-secondary border border-border shrink-0 overflow-hidden group"
              >
                <div className="absolute inset-0 bg-gradient-to-br from-muted to-secondary" />
                <span className="absolute bottom-1.5 right-1.5 text-xs text-muted-foreground font-medium">
                  {i + 1}
                </span>
                <button 
                  onClick={() => removePage(i)}
                  className="absolute top-1 right-1 w-5 h-5 rounded-full bg-destructive flex items-center justify-center opacity-0 group-hover:opacity-100 transition-opacity"
                >
                  <X className="w-3 h-3 text-destructive-foreground" />
                </button>
              </div>
            ))}
            <button className="w-14 h-18 rounded-xl border-2 border-dashed border-border flex items-center justify-center shrink-0 hover:border-primary/50 transition-colors">
              <span className="text-xl text-muted-foreground">+</span>
            </button>
          </div>
        )}
      </div>

      {/* Metadata Sheet */}
      <ScanMetadataSheet
        isOpen={showMetadataSheet}
        onClose={() => setShowMetadataSheet(false)}
        onUpload={handleUpload}
        pageCount={pageCount}
      />

      <style>{`
        @keyframes scan {
          0%, 100% { top: 0; opacity: 0; }
          10% { opacity: 1; }
          90% { opacity: 1; }
          100% { top: 100%; opacity: 0; }
        }
      `}</style>
    </div>
  );
};

export default ScannerScreen;
