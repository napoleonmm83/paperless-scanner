import { Github, FileText } from "lucide-react";

const Footer = () => {
  return (
    <footer className="py-12 border-t border-border">
      <div className="container">
        <div className="flex flex-col md:flex-row items-center justify-between gap-6">
          <div className="flex items-center gap-3">
            <div className="w-10 h-10 rounded-xl bg-primary/10 flex items-center justify-center">
              <FileText className="w-5 h-5 text-primary" />
            </div>
            <div>
              <h3 className="font-semibold">Paperless Scanner</h3>
              <p className="text-sm text-muted-foreground">Open Source Document Scanner</p>
            </div>
          </div>

          <div className="flex items-center gap-6">
            <a 
              href="https://docs.paperless-ngx.com" 
              target="_blank" 
              rel="noopener noreferrer"
              className="text-sm text-muted-foreground hover:text-foreground transition-colors"
            >
              Paperless-ngx Docs
            </a>
            <a 
              href="https://github.com/napoleonmm83/paperless-scanner" 
              target="_blank" 
              rel="noopener noreferrer"
              className="flex items-center gap-2 text-sm text-muted-foreground hover:text-foreground transition-colors"
            >
              <Github className="w-4 h-4" />
              GitHub
            </a>
          </div>
        </div>

        <div className="mt-8 pt-8 border-t border-border text-center text-sm text-muted-foreground">
          <p>
            Made with ♥ for the Paperless-ngx community
          </p>
        </div>
      </div>
    </footer>
  );
};

export default Footer;
