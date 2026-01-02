import { LucideIcon } from "lucide-react";

interface FeatureCardProps {
  icon: LucideIcon;
  title: string;
  description: string;
  features: string[];
}

const FeatureCard = ({ icon: Icon, title, description, features }: FeatureCardProps) => {
  return (
    <div className="glass-card-hover p-6 space-y-4">
      <div className="feature-icon">
        <Icon className="w-6 h-6" />
      </div>
      <div className="space-y-2">
        <h3 className="text-lg font-semibold">{title}</h3>
        <p className="text-sm text-muted-foreground">{description}</p>
      </div>
      <ul className="space-y-2">
        {features.map((feature, index) => (
          <li key={index} className="flex items-start gap-2 text-sm text-muted-foreground">
            <span className="w-1.5 h-1.5 rounded-full bg-primary mt-2 shrink-0" />
            {feature}
          </li>
        ))}
      </ul>
    </div>
  );
};

export default FeatureCard;
