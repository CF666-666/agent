import * as React from "react";
import { Info, Menu } from "lucide-react";
import { Link } from "react-router-dom";

import { Button } from "@/components/ui/button";
import { useChatStore } from "@/stores/chatStore";

interface HeaderProps {
  onToggleSidebar: () => void;
}

export function Header({ onToggleSidebar }: HeaderProps) {
  const { currentSessionId, sessions } = useChatStore();
  const currentSession = React.useMemo(
    () => sessions.find((session) => session.id === currentSessionId),
    [sessions, currentSessionId]
  );

  return (
    <header className="sticky top-0 z-20 bg-white">
      <div className="flex h-16 items-center justify-between px-6">
        <div className="flex items-center gap-2">
          <Button
            variant="ghost"
            size="icon"
            onClick={onToggleSidebar}
            aria-label="切换侧边栏"
            className="text-gray-500 hover:bg-gray-100 lg:hidden"
          >
            <Menu className="h-5 w-5" />
          </Button>
          <p className="text-base font-medium text-gray-900">
            {currentSession?.title || "新对话"}
          </p>
        </div>
        <div className="flex items-center gap-2">
          <Link
            to="/about"
            className="flex items-center gap-1.5 rounded-xl border border-gray-200 px-3 py-1.5 text-sm text-gray-600 transition hover:bg-gray-100 hover:text-gray-900"
            aria-label="关于项目"
          >
            <Info className="h-4 w-4" />
            <span className="font-medium">关于项目</span>
          </Link>
        </div>
      </div>
    </header>
  );
}
