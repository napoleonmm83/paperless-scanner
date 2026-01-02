import { useState } from "react";
import WelcomeScreen from "./WelcomeScreen";
import ServerSetupScreen from "./ServerSetupScreen";
import LoginScreen from "./LoginScreen";
import SuccessScreen from "./SuccessScreen";

type OnboardingStep = "welcome" | "server" | "login" | "success";

interface OnboardingFlowProps {
  onComplete: () => void;
}

const OnboardingFlow = ({ onComplete }: OnboardingFlowProps) => {
  const [currentStep, setCurrentStep] = useState<OnboardingStep>("welcome");

  switch (currentStep) {
    case "welcome":
      return <WelcomeScreen onContinue={() => setCurrentStep("server")} />;
    case "server":
      return (
        <ServerSetupScreen
          onBack={() => setCurrentStep("welcome")}
          onContinue={() => setCurrentStep("login")}
        />
      );
    case "login":
      return (
        <LoginScreen
          onBack={() => setCurrentStep("server")}
          onContinue={() => setCurrentStep("success")}
        />
      );
    case "success":
      return <SuccessScreen onComplete={onComplete} />;
    default:
      return <WelcomeScreen onContinue={() => setCurrentStep("server")} />;
  }
};

export default OnboardingFlow;
