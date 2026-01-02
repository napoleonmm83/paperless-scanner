import { FileText, Camera, Tags, Upload, Check, Plus } from "lucide-react";

const PhoneMockup = () => {
  return (
    <div className="phone-mockup w-[280px] animate-float">
      <div className="phone-screen aspect-[9/19] relative">
        {/* Status bar */}
        <div className="flex items-center justify-between px-6 py-3 bg-card">
          <span className="text-xs text-muted-foreground">9:41</span>
          <div className="flex gap-1">
            <div className="w-4 h-2 rounded-sm bg-primary" />
          </div>
        </div>

        {/* App content */}
        <div className="p-4 space-y-4">
          {/* Header */}
          <div className="flex items-center justify-between">
            <h3 className="text-lg font-semibold">Paperless Scanner</h3>
            <div className="w-8 h-8 rounded-full bg-primary/20 flex items-center justify-center">
              <FileText className="w-4 h-4 text-primary" />
            </div>
          </div>

          {/* Scan preview */}
          <div className="relative aspect-[4/3] rounded-2xl bg-gradient-to-br from-secondary to-muted overflow-hidden">
            <div className="absolute inset-4 border-2 border-dashed border-primary/50 rounded-xl flex items-center justify-center">
              <div className="text-center space-y-2">
                <Camera className="w-8 h-8 text-primary mx-auto" />
                <p className="text-xs text-muted-foreground">Dokument scannen</p>
              </div>
            </div>
            {/* Corner markers */}
            <div className="absolute top-2 left-2 w-4 h-4 border-t-2 border-l-2 border-primary rounded-tl" />
            <div className="absolute top-2 right-2 w-4 h-4 border-t-2 border-r-2 border-primary rounded-tr" />
            <div className="absolute bottom-2 left-2 w-4 h-4 border-b-2 border-l-2 border-primary rounded-bl" />
            <div className="absolute bottom-2 right-2 w-4 h-4 border-b-2 border-r-2 border-primary rounded-br" />
          </div>

          {/* Recent scans */}
          <div className="space-y-3">
            <div className="flex items-center justify-between">
              <span className="text-sm font-medium">Letzte Scans</span>
              <span className="text-xs text-primary">Alle anzeigen</span>
            </div>

            {/* Scan items */}
            <div className="space-y-2">
              {[
                { title: "Rechnung_2024.pdf", status: "uploaded", pages: 2 },
                { title: "Vertrag.pdf", status: "uploading", pages: 5 },
              ].map((doc, i) => (
                <div
                  key={i}
                  className="flex items-center gap-3 p-3 rounded-xl bg-secondary/50"
                >
                  <div className="w-10 h-10 rounded-lg bg-primary/10 flex items-center justify-center">
                    <FileText className="w-5 h-5 text-primary" />
                  </div>
                  <div className="flex-1 min-w-0">
                    <p className="text-sm font-medium truncate">{doc.title}</p>
                    <p className="text-xs text-muted-foreground">
                      {doc.pages} Seiten
                    </p>
                  </div>
                  {doc.status === "uploaded" ? (
                    <div className="w-6 h-6 rounded-full bg-primary/20 flex items-center justify-center">
                      <Check className="w-3 h-3 text-primary" />
                    </div>
                  ) : (
                    <div className="w-6 h-6 rounded-full border-2 border-primary border-t-transparent animate-spin" />
                  )}
                </div>
              ))}
            </div>
          </div>

          {/* Quick actions */}
          <div className="flex gap-2">
            <button className="flex-1 flex items-center justify-center gap-2 py-3 rounded-xl bg-primary text-primary-foreground text-sm font-medium">
              <Camera className="w-4 h-4" />
              Scannen
            </button>
            <button className="flex items-center justify-center w-12 h-12 rounded-xl bg-secondary">
              <Plus className="w-5 h-5" />
            </button>
          </div>
        </div>

        {/* Bottom nav */}
        <div className="absolute bottom-0 left-0 right-0 flex items-center justify-around py-4 px-6 bg-card border-t border-border">
          <Camera className="w-6 h-6 text-primary" />
          <FileText className="w-6 h-6 text-muted-foreground" />
          <Tags className="w-6 h-6 text-muted-foreground" />
          <Upload className="w-6 h-6 text-muted-foreground" />
        </div>
      </div>
    </div>
  );
};

export default PhoneMockup;
