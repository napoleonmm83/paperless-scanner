import { useState } from "react";
import { AnimatePresence, motion } from "framer-motion";
import BottomNav from "@/components/app/BottomNav";
import HomeScreen from "@/components/app/HomeScreen";
import ScannerScreen from "@/components/app/ScannerScreen";
import DocumentsScreen from "@/components/app/DocumentsScreen";
import LabelsScreen from "@/components/app/LabelsScreen";
import SettingsScreen from "@/components/app/SettingsScreen";
import OnboardingFlow from "@/components/onboarding/OnboardingFlow";

export type Screen = "home" | "scanner" | "documents" | "labels" | "settings";

const screenOrder: Record<Screen, number> = {
  home: 0,
  documents: 1,
  scanner: 2,
  labels: 3,
  settings: 4,
};

const AppDemo = () => {
  const [isOnboarded, setIsOnboarded] = useState(false);
  const [currentScreen, setCurrentScreen] = useState<Screen>("home");
  const [previousScreen, setPreviousScreen] = useState<Screen>("home");

  const handleNavigate = (screen: Screen) => {
    setPreviousScreen(currentScreen);
    setCurrentScreen(screen);
  };

  const direction = screenOrder[currentScreen] > screenOrder[previousScreen] ? 1 : -1;

  // Show onboarding if not completed
  if (!isOnboarded) {
    return (
      <div className="min-h-screen bg-background flex flex-col max-w-md mx-auto relative overflow-hidden">
        <OnboardingFlow onComplete={() => setIsOnboarded(true)} />
      </div>
    );
  }

  const renderScreen = () => {
    switch (currentScreen) {
      case "home":
        return <HomeScreen onNavigate={handleNavigate} />;
      case "scanner":
        return <ScannerScreen />;
      case "documents":
        return <DocumentsScreen />;
      case "labels":
        return <LabelsScreen />;
      case "settings":
        return <SettingsScreen />;
      default:
        return <HomeScreen onNavigate={handleNavigate} />;
    }
  };

  const pageVariants = {
    initial: (dir: number) => ({
      x: dir > 0 ? 60 : -60,
      opacity: 0,
    }),
    animate: {
      x: 0,
      opacity: 1,
    },
    exit: (dir: number) => ({
      x: dir > 0 ? -60 : 60,
      opacity: 0,
    }),
  };

  const pageTransition = {
    type: "tween" as const,
    ease: [0.25, 0.46, 0.45, 0.94] as [number, number, number, number],
    duration: 0.25,
  };

  return (
    <div className="min-h-screen bg-background flex flex-col max-w-md mx-auto relative overflow-hidden">
      {/* Status bar simulation */}
      <div className="flex items-center justify-between px-6 py-2 bg-card border-b border-border">
        <span className="text-xs text-muted-foreground font-medium">9:41</span>
        <div className="flex items-center gap-1">
          <div className="flex gap-0.5">
            <div className="w-1 h-2 bg-foreground/60 rounded-sm" />
            <div className="w-1 h-3 bg-foreground/60 rounded-sm" />
            <div className="w-1 h-4 bg-foreground/60 rounded-sm" />
            <div className="w-1 h-3 bg-foreground/40 rounded-sm" />
          </div>
          <div className="w-6 h-3 rounded-sm bg-primary ml-1 relative">
            <div className="absolute right-0 top-1/2 -translate-y-1/2 -right-0.5 w-0.5 h-1.5 bg-primary rounded-r-full" />
          </div>
        </div>
      </div>

      {/* Screen content with animation */}
      <div className="flex-1 overflow-hidden relative">
        <AnimatePresence mode="wait" custom={direction}>
          <motion.div
            key={currentScreen}
            custom={direction}
            variants={pageVariants}
            initial="initial"
            animate="animate"
            exit="exit"
            transition={pageTransition}
            className="absolute inset-0"
          >
            {renderScreen()}
          </motion.div>
        </AnimatePresence>
      </div>

      {/* Bottom navigation */}
      <BottomNav currentScreen={currentScreen} onNavigate={handleNavigate} />
    </div>
  );
};

export default AppDemo;
