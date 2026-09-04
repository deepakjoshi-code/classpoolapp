import { describe, expect, it, vi, beforeEach } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { JoinForm } from "@/components/JoinForm";
import { api } from "@/lib/api/client";
import type { Membership } from "@/lib/api/types";

vi.mock("@/lib/api/client", () => ({
  api: {
    GET: vi.fn(),
    POST: vi.fn(),
  },
  API_BASE_URL: "/api/v1",
}));

const mockedApi = vi.mocked(api);

const membership: Membership = {
  id: "membership-1",
  classroomId: "classroom-1",
  role: "PARENT",
  studentId: "student-1",
  studentFirstName: "Alex",
  lateJoin: false,
  classroom: {
    id: "classroom-1",
    schoolId: "school-1",
    schoolName: "Lincoln Elementary",
    schoolYearLabel: "2026-2027",
    grade: "Grade 1",
    teacherLabel: "Ms. Smith",
    studentCountEstimate: 24,
    createdAt: new Date().toISOString(),
  },
};

describe("JoinForm", () => {
  beforeEach(() => {
    mockedApi.POST.mockReset();
  });

  it("renders a student-name field and a submit button, with submit disabled until filled", () => {
    render(<JoinForm token="7H2KQ" onJoined={vi.fn()} />);

    const input = screen.getByLabelText(/child's first name/i);
    const button = screen.getByRole("button", { name: /join this class/i });

    expect(input).toBeInTheDocument();
    expect(button).toBeDisabled();
  });

  it("submits the student name and calls the join endpoint, then reports the result", async () => {
    mockedApi.POST.mockResolvedValue({ data: membership, error: undefined } as any);
    const onJoined = vi.fn();
    const user = userEvent.setup();

    render(<JoinForm token="7H2KQ" onJoined={onJoined} />);

    await user.type(screen.getByLabelText(/child's first name/i), "Alex");
    await user.click(screen.getByRole("button", { name: /join this class/i }));

    await waitFor(() => expect(mockedApi.POST).toHaveBeenCalledTimes(1));
    expect(mockedApi.POST).toHaveBeenCalledWith("/invites/{token}/join", {
      params: { path: { token: "7H2KQ" } },
      body: { studentFirstName: "Alex" },
    });

    await waitFor(() => expect(onJoined).toHaveBeenCalledWith(membership));
  });

  it("shows an error and does not call onJoined when the join fails", async () => {
    mockedApi.POST.mockResolvedValue({
      data: undefined,
      error: { message: "expired" },
    } as any);
    const onJoined = vi.fn();
    const user = userEvent.setup();

    render(<JoinForm token="EXPIRED" onJoined={onJoined} />);

    await user.type(screen.getByLabelText(/child's first name/i), "Alex");
    await user.click(screen.getByRole("button", { name: /join this class/i }));

    expect(await screen.findByRole("alert")).toHaveTextContent(/couldn't join/i);
    expect(onJoined).not.toHaveBeenCalled();
  });
});
